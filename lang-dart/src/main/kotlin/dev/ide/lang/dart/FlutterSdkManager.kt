package dev.ide.lang.dart

import dev.ide.android.support.tools.HttpSdkNetFetcher
import dev.ide.android.support.tools.SdkNetFetcher
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

/**
 * On-demand Dart SDK installer for the Dart/Flutter toolchain — the Dart/Flutter analogue of how CodeStudio
 * acquires its JDK / Android sources: download on first use, keep it in a shared `.platform` dir, and reuse
 * across runs. The Dart SDK ships as a plain `.zip` and extracts with vanilla `ZipInputStream` (mirroring how
 * [dev.ide.core.JdkManager] unpacks a JDK zip in pure Java) — no external `unzip`/`tar`, which an Android app
 * process cannot exec.
 *
 * The full Flutter SDK ships only as `.tar.xz` (needs an in-process LZMA2 decoder and cannot, today, produce a
 * runnable APK on a sandboxed device — see the build system's messaging). This installer therefore provisions
 * the **Dart** SDK, which yields a real `dart` VM/compiler that the `dart-console` module type actually runs
 * with (`dart run`), and which the `flutter`-bootstrap shared by Flutter projects expects on the `PATH`.
 */
class FlutterSdkManager(private val fetcher: SdkNetFetcher = HttpSdkNetFetcher) {

    /** The platform Android-suffix for this host (`linux`/`macos`/`windows`), or null if unknown. */
    private fun hostPlatform(): String? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("win") -> "windows"
            os.contains("mac") || os.contains("darwin") -> "macos"
            os.contains("nux") || os.contains("nix") -> "linux"
            else -> null
        }
    }

    private fun hostArch(): String {
        val a = System.getProperty("os.arch").orEmpty().lowercase()
        return if (a.contains("aarch64") || a.contains("arm64")) "arm64" else "x64"
    }

    /** `<workspace>/.platform/dart-sdk` — the shared writable toolchain dir (same convention as the SDK manager). */
    private fun sdkRoot(workspaceRoot: Path): Path = workspaceRoot.resolve(".platform").resolve("dart-sdk")

    private fun extractedRoot(workspaceRoot: Path): Path = sdkRoot(workspaceRoot).resolve("dart-sdk")

    /** Whether a usable Dart SDK is installed under [workspaceRoot]. */
    fun isInstalled(workspaceRoot: Path): Boolean = Files.isExecutable(dartBin(workspaceRoot))

    /** The `dart` executable of the installed SDK (or null if not installed). */
    fun dartBin(workspaceRoot: Path): Path {
        val bin = extractedRoot(workspaceRoot).resolve("bin")
        val exe = if (hostPlatform() == "windows") "dart.exe" else "dart"
        return bin.resolve(exe)
    }

    /** The `flutter` bootstrap script inside an installed Flutter SDK, should one be placed under the same root. */
    fun flutterBin(workspaceRoot: Path): Path {
        val bin = sdkRoot(workspaceRoot).resolve("flutter").resolve("bin")
        val exe = if (hostPlatform() == "windows") "flutter.bat" else "flutter"
        return bin.resolve(exe)
    }

    /**
     * Download and install the Dart SDK under [workspaceRoot] if not already present. Returns an empty string
     * on success or a human-readable error/status. Reports progress through [onProgress].
     */
    fun ensureDownloaded(workspaceRoot: Path, onProgress: (read: Long, total: Long) -> Unit = { _, _ -> }): String {
        val platform = hostPlatform() ?: return "Unsupported OS for the Dart SDK download."
        if (isInstalled(workspaceRoot)) return ""
        val root = sdkRoot(workspaceRoot)
        val url = "https://storage.googleapis.com/dart-archive/channels/stable/release/latest/sdk/" +
            "dartsdk-$platform-${hostArch()}-release.zip"
        val archive = Files.createTempFile("dartsdk", ".zip")
        try {
            if (!fetcher.download(url, archive, onProgress)) return "Dart SDK download failed."
            Files.createDirectories(root)
            val staging = root.resolve("staging-" + System.nanoTime())
            Files.createDirectories(staging)
            val extracted = extractZip(archive, staging)
            if (extracted == null) return "Downloaded archive was not a usable Dart SDK."
            // Remove any prior partial install; atomically move the new one into place.
            val target = extractedRoot(workspaceRoot)
            runCatching { deleteRecursively(target) }
            try {
                Files.move(extracted, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(extracted, target)
            }
            runCatching { deleteRecursively(staging) }
            return if (Files.isExecutable(dartBin(workspaceRoot))) "" else "Dart SDK installed but the 'dart' binary was not found."
        } catch (e: Exception) {
            return "Dart SDK download failed: ${e.message}"
        } finally {
            runCatching { Files.deleteIfExists(archive) }
        }
    }

    /** Extract the SDK zip under [dest]; returns the single top-level dir if the archive has a clean root. */
    private fun extractZip(archive: Path, dest: Path): Path? {
        var root: Path? = null
        ZipInputStream(Files.newInputStream(archive)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val target = dest.resolve(e.name)
                    runCatching { Files.createDirectories(target.parent) }
                    Files.newOutputStream(target).use { out -> zis.copyTo(out) }
                } else {
                    val target = dest.resolve(e.name)
                    runCatching { Files.createDirectories(target) }
                    if (root == null && e.name.removeSuffix("/").substringBefore('/') != "") {
                        root = target
                    }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        // The Dart SDK zip has a single top-level `dart-sdk/` dir with `bin/dart`.
        val candidate = dest.resolve("dart-sdk")
        return if (Files.isDirectory(candidate)) candidate
               else root?.takeIf { Files.isDirectory(it.resolve("bin")) }
    }

    private fun deleteRecursively(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } } }
    }
}
