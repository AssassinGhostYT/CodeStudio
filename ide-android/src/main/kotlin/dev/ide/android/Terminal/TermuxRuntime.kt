package dev.ide.android.Terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import android.widget.Toast
import com.termux.app.TermuxInstaller
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * On-device Termux-userland engine — the "bootstrap + shell" runtime shipped by the vendored
 * `:termux:application`'s `libtermux-bootstrap.so` (JNI `TermuxInstaller.getZip()`).
 *
 * ## Why `/system/bin/linker64` + a bundled `libexecbridge.so` instead of proot
 *
 * The Interpreter-shipped Termux ELF binaries (e.g. `bin/bash`, interp `/system/bin/linker64`,
 * NEEDED libs Termux-built living under `<filesDir>/usr/lib`) cannot be exec'd directly from
 * app-private storage on modern Android: SELinux denies `execute_no_trans` on `app_data_file`
 * for untrusted apps (targetSdk >= 29) — a W^X policy. The observed failure is
 * `exec(.../usr/bin/bash): Permission denied`. Even the bundled proot can't help: it fast-paths
 * host-ELF binaries (machine == host, interp linker64) straight to kernel execve, which is the
 * very call SELinux blocks.
 *
 * The canonical Termux solution is exactly what `libtermux-exec.so` provides. Since Android 10
 * the system dynamic linker can be invoked directly:
 *
 *     /system/bin/linker64 /abs/path/to/mybinary
 *
 * and it will dlopen + run the binary from app-data. SELinux only ever sees `system_linker_exec`
 * (which untrusted_app IS allowed to execute), so the call succeeds. Our bundled `libexecbridge.so` is a
 * `LD_PRELOAD` shim whose overridden `exec(3)` family (execve/execvp/execvpe) rewrites every exec
 * of a Termux binary into `/system/bin/linker64 <bin> …` transparently. We therefore:
 *
 *   1. Launch the *first* process as `/system/bin/linker64 <prefix>/bin/sh` (or bash) with
 *      `LD_PRELOAD=<nativeLibDir>/libexecbridge.so`, `LD_LIBRARY_PATH=<prefix>/lib:/system/lib64`.
 *      The linker is a permitted exec target, so it never hits the SELinux wall.
 *   2. Every child it spawns inherits the same env, so the bundled shim keeps intercepting their
 *      exec calls — the whole userland (dash, node, python, apt-get, dpkg…) runs without a single
 *      direct kernel exec of an app-data ELF.
 *
 * apt-get/dpkg hardcode `/data/data/com.termux/files/usr` in their maintainer scripts and metadata.
 * Rather than remap via proot (which can't exec host-ELF either), we write an `etc/apt/apt.conf`
 * with `Dir "/"` and every directory pointing at our real prefix — the same approach OpenClaw's
 * Android runtime uses, and it avoids proot entirely.
 *
 * ## Extraction
 *
 * The bootstrap zip embedded in `libtermux-bootstrap.so` is extracted to `<filesDir>/usr`, with
 * SYMLINKS.txt (`src←dst`, U+2190) recreated via `Os.symlink`, exactly like the vendored
 * `TermuxInstaller`. One of the bundled termux-exec variants is installed as
 * `usr/lib/libtermux-exec.so` (the standard name Termux uses for the preload shim; our env now
 * overrides `LD_PRELOAD` to the bundled `libexecbridge.so` which performs the same rewrite).
 */
object TermuxRuntime : TerminalSessionClient, TerminalRuntime {

    private const val TAG = "TermuxRuntime"
    private const val BOOTSTRAP_MARKER = ".cs-termux-bootstrap-v1"

    private val _setup = MutableStateFlow<TerminalSetupState>(TerminalSetupState.Idle)
    override val setup: StateFlow<TerminalSetupState> = _setup
    private val _running = MutableStateFlow(false)
    override val running: StateFlow<Boolean> = _running

    private var filesDir: File? = null
    private var appContext: Context? = null
    override var session: TerminalSession? = null

    override fun init(context: Context) {
        appContext = context.applicationContext
        filesDir = context.applicationContext.filesDir
        Log.i(TAG, "init; filesDir=${filesDir?.absolutePath}")
    }

    private fun prefixDir() = File(filesDir!!, "usr")
    private fun homeDir() = File(filesDir!!, "home").apply { mkdirs() }
    private fun tmpDir() = File(filesDir!!, "tmp").apply { mkdirs() }
    private fun scriptsDir() = File(filesDir!!, "scripts").apply { mkdirs() }
    private fun termuxExec() = File(prefixDir(), "lib/libtermux-exec.so")
    private fun execBridge() = File(
        appContext?.applicationInfo?.nativeLibraryDir ?: "/data/local/tmp",
        "libexecbridge.so",
    )
    private fun linker() = if (File("/system/bin/linker64").exists()) "/system/bin/linker64" else "/system/bin/linker"

    override suspend fun ensureReady(onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (_setup.value is TerminalSetupState.Ready) return@withContext
        if (_setup.value is TerminalSetupState.Downloading || _setup.value is TerminalSetupState.Extracting) return@withContext
        try {
            val prefix = prefixDir()
            _setup.value = TerminalSetupState.Downloading("Bootstrap Termux (~30 MB embebido)…")
            extractBootstrapOnce(prefix, onProgress)
            installTermuxExec(prefix)
            patchTermuxExecShebangs(prefix)
            writeAptConfig(prefix)
            scrubStalePaths(prefix)
            healTermuxState(prefix)
            installBionicCompat()
            writeSessionScript()
            writeShellConfig(prefix)
            writePkgWrapper(prefix)
            writeDpkgWrapper(prefix)
            onProgress("Termux listo en ${prefix.absolutePath}")
            _setup.value = TerminalSetupState.Ready
            Log.i(TAG, "Termux userland ready at ${prefix.absolutePath}")
        } catch (e: Throwable) {
            Log.e(TAG, "ensureReady failed", e)
            _setup.value = TerminalSetupState.Failed(e.message ?: "setup failed")
        }
    }

    private fun extractBootstrapOnce(prefix: File, onProgress: (String) -> Unit) {
        if (File(prefix, BOOTSTRAP_MARKER).exists() && File(prefix, "bin/bash").exists()) {
            onProgress("Reutilizando bootstrap Termux")
            return
        }
        prefix.deleteRecursively()
        prefix.mkdirs()
        onProgress("Extrayendo archivos…")
        val blob = try {
            TermuxInstaller.loadZipBytes()
        } catch (e: Throwable) {
            throw IllegalStateException("No se pudo cargar libtermux-bootstrap.so: ${e.message}", e)
        }
        if (blob == null || blob.isEmpty()) throw IllegalStateException("Bootstrap vacío (libtermux-bootstrap.so)")
        extractBootstrapZip(blob, prefix)
        File(prefix, BOOTSTRAP_MARKER).writeText("ok\n")
        ensureExecutable(File(prefix, "bin/bash"))
        ensureExecutable(File(prefix, "bin/sh"))
        ensureExecutable(File(prefix, "bin/ln"))
    }

    /**
     * ZIP extractor mirroring the vendored TermuxInstaller semantics: the SYMLINKS.txt entry lists
     * `src←dst` pairs (U+2190 separator) whose targets are recreated as symlinks after extraction;
     * entries under bin/ and the lib/apt helpers get exec bits slapped on.
     */
    fun extractBootstrapZip(blob: ByteArray, prefix: File) {
        val symlinks = mutableListOf<Pair<String, String>>()
        ZipInputStream(ByteArrayInputStream(blob)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name == "SYMLINKS.txt") {
                    String(zis.readBytes(), Charsets.UTF_8).lineSequence().forEach { line ->
                        val parts = line.trim().split("\u2190")
                        if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                            symlinks.add(parts[0] to parts[1])
                        }
                    }
                } else if (!entry.isDirectory) {
                    val target = File(prefix, name)
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> zis.rawCopy(out) }
                    val execPath = name.startsWith("bin/") || name.startsWith("libexec") ||
                        name.startsWith("lib/apt/apt-helper") || name.startsWith("lib/apt/methods")
                    if (execPath) ensureExecutable(target)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (symlinks.isEmpty()) throw IllegalStateException("No SYMLINKS.txt en bootstrap")
        for ((src, dst) in symlinks) {
            val link = File(prefix, dst)
            link.parentFile?.mkdirs()
            runCatching { Os.symlink(src, link.absolutePath) }
                .onFailure { Log.w(TAG, "symlink $src -> ${link.absolutePath} fallo: ${it.message}") }
        }
    }

    /**
     * Install the termux-exec preload shim under the standard name `usr/lib/libtermux-exec.so`.
     * The `direct-ld-preload` variant is the one built to intercept the exec family and rewrite to
     * `/system/bin/linker64` — the mechanism this engine depends on (see class KDoc).
     *
     * IMPORTANT: the bootstrap zip ALREADY ships `lib/libtermux-exec.so` as a symlink →
     * `libtermux-exec-ld-preload.so` (the *indirect* variant, which tries kernel execve first and
     * only falls back to the linker on EACCES). Termux's own postinst (`termux-exec-ld-preload-lib
     * setup`, run on `pkg upgrade`) REPLACES that with the direct variant when system-linker-exec
     * applies — which it always does for us (targetSdk 36, domain `untrusted_app`). This is exactly
     * the "restablecer `LD_PRELOAD`" step from the termux-exec README. So a plain
     * `if (target.exists()) return` would leave the factory indirect variant active and the direct
     * one never installed; this function deliberately drops the symlink and puts the direct bytes
     * at the exported `LD_PRELOAD` path.
     */
    private fun installTermuxExec(prefix: File) {
        val marker = File(prefix, "lib/.cs-termux-exec-direct")
        val target = termuxExec()
        val direct = File(prefix, "lib/libtermux-exec-direct-ld-preload.so")
        if (marker.exists() && target.isFile) return
        if (!direct.exists()) {
            // If the bootstrap lacks the direct variant, keep whatever the zip shipped (indirect)
            // rather than leaving LD_PRELOAD dangling — but log loudly, since the exec rewrite is
            // then best-effort only.
            Log.e(TAG, "libtermux-exec-direct-ld-preload.so ausente en bootstrap; termux-exec quedará en variante indirecta")
            return
        }
        target.delete() // drop the zip's symlink → libtermux-exec-ld-preload.so (indirect variant)
        direct.copyTo(target, overwrite = true)
        ensureExecutable(target)
        marker.writeText(direct.name)
        Log.i(TAG, "termux-exec (directo) instalado en $target")
    }

    /**
     * The vendored bootstrap was built from AndroidIDE, so the termux-exec helper scripts
     * (`bin/termux-exec-ld-preload-lib`, `bin/termux-exec-system-linker-exec`) carry a stale
     * shebang `#!/data/data/com.tom.rv2ide/files/usr/bin/sh` that does not exist on this device.
     * Maintainer scripts (dpkg) invoke them on `pkg upgrade`/install; with the AndroidIDE path the
     * kernel fails the shebang exec ("No such file or directory") and the operation dies. Rewrite
     * the shebang to `/system/bin/sh` (always present; a kernel exec of OUR prefix sh would hit the
     * same SELinux wall we're routing around via the linker, so host sh is the right interpreter).
     */
    private fun patchTermuxExecShebangs(prefix: File) {
        for (name in listOf(
            "bin/termux-exec-ld-preload-lib",
            "bin/termux-exec-system-linker-exec",
        )) {
            val f = File(prefix, name)
            if (!f.exists()) continue
            val text = f.readText()
            val fixed = text.replace("#!/data/data/com.tom.rv2ide/files/usr/bin/sh", "#!/system/bin/sh")
            if (fixed != text) {
                f.writeText(fixed)
                Log.i(TAG, "patchTermuxExecShebangs: corregido shebang en $name")
            }
        }
    }

    /**
     * apt-get/dpkg on stock Termux assume `/data/data/com.termux/files/usr`. Without proot we can't
     * bind-mount our real prefix onto that path, so instead mirror OpenClaw's fix: an `etc/apt/apt.conf`
     * with `Dir "/"` and every path pointed at our real prefix. `buildEnvironment()` sets `APT_CONFIG`
     * to this file so apt actually reads it (its compiled-in `Dir::Etc` is the stale rv2ide path).
     */
    private fun writeAptConfig(prefix: File) {
        val aptConf = File(prefix, "etc/apt/apt.conf")
        val p = prefix.absolutePath
        // Termux .debs bake the FULL absolute prefix (/data/data/com.termux/files/usr, or the
        // AAIDE flavor /data/data/com.tom.rv2ide/files/usr) into every data.tar member. To make
        // dpkg unpack them into OUR prefix (filesDir/usr) without proot, point --instdir at the
        // files dir and create a symlink farm so the baked-in app path resolves to our prefix:
        //   filesDir/data/data/{com.termux,com.tom.rv2ide,com.codestudio.ide}/files/usr -> filesDir/usr
        // dpkg then follows the symlink and files land exactly where the bootstrap put them.
        ensurePrefixSymlinkFarm(filesDir!!, prefix)
        // This AAIDE bootstrap's termux-tools postinst never ran (it assumed com.tom.rv2ide), so the
        // apt dirs it was supposed to create are missing. apt hard-fails when these don't exist, so
        // create them here (idempotent; also run on every startSession to heal existing installs).
        listOf(
            "$p/etc/apt/apt.conf.d",
            "$p/etc/apt/preferences.d",
            "$p/etc/apt/sources.list.d",
            "$p/var/cache/apt/archives/partial",
            "$p/var/cache/apt/lists/partial",
            "$p/var/lib/apt/lists/partial",
            "$p/var/lib/dpkg/updates",
            "$p/var/log/apt",
        ).forEach { mkdirsQuiet(File(it)) }
        aptConf.writeText(
            """
            Dir "/";
            Dir::State "$p/var/lib/apt/";
            Dir::State::status "$p/var/lib/dpkg/status";
            Dir::Cache "$p/var/cache/apt/";
            Dir::Log "$p/var/log/apt/";
            Dir::Etc "$p/etc/apt/";
            Dir::Etc::SourceList "$p/etc/apt/sources.list";
            Dir::Etc::SourceParts "";
            Dir::Bin::dpkg "$p/bin/_cs-dpkg";
            Dir::Bin::Methods "$p/lib/apt/methods/";
            Dir::Bin::apt-key "$p/bin/apt-key";
            DPkg::Post-Invoke "$p/bin/_cs-heal $p >/dev/null 2>&1 || true";
            Dpkg::Options:: "--force-configure-any";
            Dpkg::Options:: "--force-bad-path";
            // Termux .debs bake the FULL absolute prefix into every member (data/data/com.termux/…,
            // AAIDE flavor data/data/com.tom.rv2ide/…). --instdir=$p used to leave them NESTED under
            // $p/data/data/… (why node/npm/wget installed but never appeared on PATH). Point instdir
            // at the app FILES dir; the symlink farm in ensurePrefixSymlinkFarm then maps
            // filesDir/data/data/<app>/files/usr -> filesDir/usr so files land in the real prefix.
            Dpkg::Options:: "--instdir=${filesDir?.absolutePath}";
            Acquire::AllowInsecureRepositories "true";
            // Both the AAIDE ACS repo and official termux-main sign InRelease with keys not in this
            // bootstrap's (termux-tools never ran its postinst) keyring; lists are hash-verified so
            // installs must not be blocked on signature state.
            Acquire::AllowUnauthenticated "true";
            Acquire::https::CaInfo "${systemCaBundle().absolutePath}";
            """.trimIndent() + "\n",
        )
        Log.i(TAG, "apt.conf escrito en $aptConf")
        writeSourcesList(prefix)
    }

    /**
     * Re-map the absolute app prefixes that Termux debs are baked for onto our real prefix, so
     * dpkg (with --instdir=<filesDir>) unpacks every member exactly where the bootstrap lives.
     * Files under filesDir/data/data/<app>/files/usr that would be created as REAL dirs are
     * replaced: only the leaf `usr` is a symlink to <filesDir>/usr, matching how Termux lays out
     * its own tree but without needing /data/data/com.termux to exist on-device.
     */
    private fun ensurePrefixSymlinkFarm(filesDir: File, prefix: File) {
        val target = prefix.absolutePath
        val apps = listOf("com.termux", "com.tom.rv2ide", "com.codestudio.ide")
        for (app in apps) {
            val leaf = File(filesDir, "data/data/$app/files/usr")
            runCatching {
                val leafPath = leaf.toPath()
                Files.isSymbolicLink(leafPath).let { isLink ->
                    if (isLink) {
                        val dest = Files.readSymbolicLink(leafPath).toString()
                        if (dest == target) return@runCatching   // already correct
                        Files.delete(leafPath)                   // stale link; re-point below
                    } else if (leaf.isFile) {
                        leaf.delete()                            // stray regular file; replace
                    } else if (leaf.isDirectory) {
                        // REAL dir (not a link): leftover from an old --instdir=$p install. Note
                        // we must NOT deleteRecursively a symlink-to-dir (it would walk the actual
                        // prefix); that's why the isLink branch above always wins for links.
                        leaf.deleteRecursively()
                    }
                }
                listOf("data/data", "data/data/$app", "data/data/$app/files")
                    .forEach { segment -> File(filesDir, segment).mkdirs() }
                Os.symlink(target, leaf.absolutePath)
                Log.i(TAG, "symlink-farm: ${leaf.absolutePath} -> $target")
            }.onFailure { Log.w(TAG, "symlink-farm skip ${leaf.absolutePath}: ${it.message}") }
        }
    }

    /**
     * Point apt at the OFFICIAL termux-main repo instead of the AAIDE/ACS hobby mirror (whose debs
     * are the stale com.tom.rv2ide flavor and which lacks nodejs/npm/nodejs-lts). Official debs are
     * current, signed by termux, and their absolute /data/data/com.termux/… members resolve through
     * ensurePrefixSymlinkFarm. Idempotent; written on every startSession to heal user edits.
     */
    private fun writeSourcesList(prefix: File) {
        val sources = File(prefix, "etc/apt/sources.list")
        val p = prefix.absolutePath
        runCatching {
            val keep = if (sources.exists()) sources.readText() else ""
            val officialLine = "deb [trusted=yes] https://packages.termux.dev/apt/termux-main stable main\n"
            if (keep.contains(officialLine.trim())) {
                Log.i(TAG, "sources.list ya apunta al repo oficial")
                return
            }
            val policy =
                postInstalledSourcesListMarker() + // distinct anchor so we never re-append
                keep.lines().filter { line ->
                    val l = line.trim()
                    l.isNotEmpty() && !l.startsWith("#") && !l.startsWith("deb")
                }.joinToString("\n")
            sources.writeText(policy + (if (policy.endsWith("\n")) "" else "\n") + officialLine)
            Log.i(TAG, "sources.list redirigido a packages.termux.dev (adaptado de $p/etc/apt/sources.list)")
        }.onFailure { Log.w(TAG, "writeSourcesList: ${it.message}") }
    }

    private fun postInstalledSourcesListMarker(): String =
        "# Codestudio: repos reemplazados por termux-main oficial; reselect con 'pkg mirror-set' o reescribe\n"

    private fun mkdirsQuiet(dir: File) {
        if (dir.exists()) return
        runCatching { dir.mkdirs() }.onFailure { Log.w(TAG, "no se pudo crear ${dir.absolutePath}: $it") }
    }

    /**
     * Build a PEM CA bundle from the Android trust store. The AAIDE bootstrap's ca-certificates /
     * tls setup is broken for our package, and apt/curl/node need a real bundle: /system/etc/security/
     * cacerts (and the conscrypt APEX one) always exist, so concatenate their PEM files once into
     * $HOME/.codestudio/cacert.pem. Idempotent and recreated if missing.
     */
    private fun systemCaBundle(): File {
        val out = File(homeDir(), ".codestudio/cacert.pem")
        if (out.length() > 0) return out
        val namespaces = listOf(
            File("/apex/com.android.conscrypt/cacerts"),
            File("/system/etc/security/cacerts"),
        )
        runCatching {
            val src: List<File> = namespaces
                .filter { d -> d.isDirectory }
                .flatMap { d -> d.listFiles()?.toList() ?: emptyList() }
                .filter { f -> f.isFile && f.length() > 8 }
                .filter { f -> runCatching { String(f.readBytes().let { b -> b.copyOfRange(0, minOf(512, b.size)) }, Charsets.US_ASCII).contains("-----BEGIN") }.getOrDefault(false) }
                .sortedBy { f -> f.absolutePath }
            if (src.isEmpty()) return@runCatching
            out.parentFile?.mkdirs()
            val tmp = File(out.parentFile, "cacert.pem.tmp")
            FileOutputStream(tmp).use { fos ->
                for (f in src) {
                    runCatching {
                        val data = f.readBytes()
                        if (data.isNotEmpty()) {
                            fos.write(data, 0, data.size)
                            fos.write('\n'.code)
                        }
                    }
                }
            }
            val size = tmp.length()
            if (size > 0 && tmp.renameTo(out)) Log.i(TAG, "CA bundle generado: ${out.absolutePath} ($size bytes)")
            else tmp.delete()
        }.onFailure { Log.e(TAG, "no se pudo generar CA bundle: $it") }
        return out
    }

    /**
     * The AAIDE bootstrap was compiled for /data/data/com.tom.rv2ide, and several shell scripts /
     * maintainer scripts (pkg, postinst under var/lib/dpkg) hardcode that absolute path. Without
     * proot that dir doesn't exist, so rewriting the text files to our real prefix fixes them
     * (arbitrary length change is fine for text; ELF binaries are skipped). Guarded by a marker.
     */
    private fun scrubStalePaths(prefix: File) {
        val marker = File(homeDir(), ".codestudio/apt-scrubbed-v3")
        if (marker.exists()) return
        val staleFile = "/data/data/com.tom.rv2ide/files/usr"
        val stalePkg = "/data/data/com.tom.rv2ide"
        val real = prefix.absolutePath
        val roots = listOf("bin", "libexec", "etc", "share", "lib", "var/lib/dpkg")
            .map { File(prefix, it) }.filter { it.isDirectory }
        var fixed = 0
        var skipped = 0
        for (root in roots) {
            root.walkTopDown().forEach { f ->
                if (!f.isFile || f.length() > (4 * 1024 * 1024)) return@forEach
                runCatching {
                    val bytes = f.readBytes()
                    if (bytes.indexOf(0x01.toByte()) >= 0 || bytes.indexOf(0x00.toByte()) >= 0) {
                        skipped++ // binary/ELF: same-length-only patching, skip
                        return@forEach
                    }
                    val text = bytes.toString(Charsets.UTF_8)
                    var updated = text
                    // v1 scrubber replaced just the package name, mangling paths to <real>/files/usr;
                    // undo that before applying the clean replacements below.
                    updated = updated.replace("$real/files/usr", real)
                    updated = updated.replace(staleFile, real)
                    updated = updated.replace(stalePkg, real)
                    if (updated != text) {
                        val exec = f.canExecute()
                        f.writeText(updated)
                        if (exec) f.setExecutable(true, false)
                        fixed++
                        Log.i(TAG, "path stale corregido en ${f.absolutePath}")
                    }
                }.onFailure { skipped++ }
            }
        }
        marker.parentFile?.mkdirs()
        marker.writeText("ok: $fixed fix, $skipped skip\n")
        Log.i(TAG, "scrubStalePaths: $fixed archivos corregidos, $skipped omitidos")
    }

    /**
     * Routine heal run on every startSession (NOT marker-guarded: must repair whatever apt/dpkg
     * installs later). Fixes the three recurring user-visible breakages on the device:
     *
     * 1) Maintainer scripts (the .prerm/.preinst/.postrm/.postinst files under var/lib/dpkg/info)
     *    of BOTH the AAIDE
     *    bootstrap (shebang/paths /data/data/com.tom.rv2ide/...) and the official termux-main debs
     *    (shebang/paths /data/data/com.termux/...) point at app dirs that don't exist here; exec of
     *    update-alternatives behind their `-x` guards trips seccomp (signal 31) and kills apt
     *    upgrades. Rewrite every such absolute prefix to our real one, so files resolve.
     * 2) `termux-tools` from the AAIDE bootstrap ships Version "v1.0-1" in dpkg status — not a valid
     *    Debian version, so dpkg emits parsing warnings on every operation. Normalize it in-place.
     * 3) termux-keyring installs trusted.gpg.d entries as symlinks to /data/data/com.termux/...,
     *    which can't resolve on this device (com.termux is not installed), leaving apt's keyring
     *    empty (NO_PUBKEY warnings). Re-point them at our real share/termux-keyring dir.
     */
    private fun healTermuxState(prefix: File) {
        val real = prefix.absolutePath
        val dpkgInfo = File(prefix, "var/lib/dpkg/info")

        if (dpkgInfo.isDirectory) {
            var scriptsFixed = 0
            dpkgInfo.listFiles { f ->
                val n = f.name
                n.endsWith(".prerm") || n.endsWith(".preinst") || n.endsWith(".postrm") || n.endsWith(".postinst") || n.endsWith(".triggers")
            }?.forEach { f ->
                runCatching {
                    if (f.length() > (4 * 1024 * 1024)) return@forEach
                    val bytes = f.readBytes()
                    if (bytes.indexOf(0x01.toByte()) >= 0 || bytes.indexOf(0x00.toByte()) >= 0) return@forEach
                    val text = bytes.toString(Charsets.UTF_8)
                    var updated = text
                    for (app in listOf("com.termux", "com.tom.rv2ide", "com.codestudio.ide")) {
                        updated = updated.replace("/data/data/$app/files/usr", real)
                    }
                    // Neutralize update-alternatives guards: ANY exec of it trips seccomp (signal
                    // 31) and kills the maintainer script mid-run, failing dpkg/apt. The guard may
                    // point at a foreign app dir (com.termux, com.tom.rv2ide installed on-device),
                    // the real prefix after the rewrite above, or the $PREFIX shell var, with or
                    // without quoting — nullify them all to a path that can never exist on-device.
                    updated = updated.replace(Regex("-x\\s+\"([^\"]*update-alternatives)\""), "-x \"/data/data/null/update-alternatives\"")
                    updated = updated.replace(Regex("-x\\s+([^;\\s\"']*update-alternatives[^;\\s\"']*)"), "-x /data/data/null/update-alternatives")
                    updated = updated.replace(Regex("test -x\\s+\"([^\"]*update-alternatives)\""), "test -x \"/data/data/null/update-alternatives\"")
                    updated = updated.replace(Regex("test -x\\s+([^;\\s\"']*update-alternatives[^;\\s\"']*)"), "test -x /data/data/null/update-alternatives")
                    if (updated != text) {
                        val exec = f.canExecute()
                        f.writeText(updated)
                        if (exec) f.setExecutable(true, false)
                        scriptsFixed++
                    }
                }.onFailure { }
            }
            if (scriptsFixed > 0) Log.i(TAG, "heal: $scriptsFixed scripts de mantenimiento con prefijo corregido")
        }

        // Normalize the invalid termux-tools version (v1.0-1 -> 1.0-1) so dpkg stops warning.
        val status = File(prefix, "var/lib/dpkg/status")
        runCatching {
            if (status.exists()) {
                val text = status.readText()
                val fixed = text.replace("\nVersion: v1.0-1\n", "\nVersion: 1.0-1\n")
                if (fixed != text) { status.writeText(fixed); Log.i(TAG, "heal: version de termux-tools normalizada") }
            }
        }.onFailure { Log.w(TAG, "heal status: ${it.message}") }

        // Re-point keyring symlinks that resolve to the nonexistent com.termux app dir.
        val trustedD = File(prefix, "etc/apt/trusted.gpg.d")
        val keyRingDir = File(prefix, "share/termux-keyring")
        if (trustedD.isDirectory) {
            trustedD.listFiles { f -> f.name.endsWith(".gpg") }?.forEach { link ->
                runCatching {
                    if (link.isFile && link.length() > 0) return@forEach // real key file already present
                    // The real keys live in share/termux-keyring/, named exactly like the link
                    // (e.g. 2096779623.gpg). termux-keyring's own links point at the nonexistent
                    // com.termux app dir, so rebuild each one against our real prefix.
                    val target = File(keyRingDir, link.name)
                    if (!target.isFile) return@forEach
                    if (link.delete()) {
                        java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
                        Log.i(TAG, "heal: keyring ${link.name} -> ${target.absolutePath}")
                    }
                }.onFailure { }
            }
        }
    }

    private fun installBionicCompat() {
        val patchDir = File(homeDir(), ".codestudio/patches").apply { mkdirs() }
        val target = File(patchDir, "bionic-compat.js")
        if (target.exists()) return
        val ctx = appContext ?: return
        ctx.assets.open("bionic-compat.js").use { input ->
            FileOutputStream(target).use { input.copyTo(it) }
        }
        Log.i(TAG, "bionic-compat.js instalado en $target")
    }

    fun bionicCompatPath(): String = File(homeDir(), ".codestudio/patches/bionic-compat.js").absolutePath

    // ── Interactive session ──────────────────────────────────────────────
    // First process is /system/bin/linker64 (a permitted exec target) running bash from app-data.
    // execbridge (LD_PRELOAD, inherited by every child) rewrites subsequent execs to linker64.
    //
    // argv must be [linker, bash, "-l"] — NOT [bash, "-l"]. `execvp(linker, argv)` passes argv
    // through verbatim, so argv[0] has to be the linker path for it to enter loader mode
    // (`linker64 <elf> [args]`, the exact form termux-exec's -direct-ld-preload uses); with
    // argv[0]=bash the kernel loads linker64 as a standalone program and bash never starts.
    override fun startSession(cols: Int, rows: Int) {
        if (session != null) return
        if (_setup.value !is TerminalSetupState.Ready) {
            Log.w(TAG, "startSession before Ready; ignoring")
            return
        }
        val prefix = prefixDir()
        val linkerPath = linker()
        val bash = File(prefix, "bin/bash").absolutePath
        writeShellConfig(prefix)
        writeAptConfig(prefix)
        scrubStalePaths(prefix)
        healTermuxState(prefix)
        writePkgWrapper(prefix)
        writeDpkgWrapper(prefix)
        val args = arrayOf(linkerPath, bash, "-l")
        val env = buildEnvironment().map { (k, v) -> "$k=$v" }.toTypedArray()
        val s = TerminalSession(linkerPath, prefix.absolutePath, args, env, rows, this)
        session = s
        s.updateSize(cols, rows)
        _running.value = true
        Log.i(TAG, "Termux session started: linker=$linkerPath bash=$bash prefix=${prefix.absolutePath}")
    }

    private fun buildEnvironment(): Map<String, String> {
        val prefix = prefixDir()
        val sysEnv = System.getenv()
        return buildMap {
            put("PREFIX", prefix.absolutePath)
            put("TERMUX_PREFIX", prefix.absolutePath)
            put("TERMUX__PREFIX", prefix.absolutePath)
            // execbridge routes execs of app-data ELFs/scripts through the linker. Cover the WHOLE
            // files dir (usr/ AND home/): tools like opencode install binaries under ~/.opencode/bin,
            // outside $PREFIX, and direct exec of those would hit W^X and die with EACCES.
            put("CS_PREFIX", "${filesDir}/")
            // The termux-exec shim + its is-enabled check read the SDK level to decide between the
            // direct/linker variants (the scripts fall back to getprop; pass it explicitly so the
            // C shim never needs /system/bin/getprop).
            put("ANDROID__BUILD_VERSION_SDK", Build.VERSION.SDK_INT.toString())
            put("HOME", homeDir().absolutePath)
            // opencode's installer drops its binary in ~/.opencode/bin and pastes the export into
            // ~/.bashrc; login shells (bash -l, our session mode) never read .bashrc directly, so
            // also put it on PATH here to make `opencode` resolvable right away.
            put("PATH", "${prefix.absolutePath}/bin:${prefix.absolutePath}/bin/applets:${homeDir().absolutePath}/.opencode/bin:/system/bin:/sbin:/bin")
            put("LD_LIBRARY_PATH", "${prefix.absolutePath}/lib:/system/lib64:/system/lib")
            put("LD_PRELOAD", execBridge().absolutePath)
            put("TERM", "xterm-256color")
            put("LANG", "C.UTF-8")
            put("COLORTERM", "truecolor")
            put("TMPDIR", tmpDir().absolutePath)
            put("TMP", tmpDir().absolutePath)
            put("TEMP", tmpDir().absolutePath)
            val caBundle = systemCaBundle().absolutePath
            put("SSL_CERT_FILE", caBundle)
            put("SSL_CERT_DIR", "/system/etc/security/cacerts")
            put("CURL_CA_BUNDLE", caBundle)
            put("GIT_SSL_CAINFO", caBundle)
            put("OPENSSL_CONF", "${prefix.absolutePath}/etc/tls/openssl.cnf")
            // This AAIDE-class bootstrap compiled apt/dpkg against /data/data/com.tom.rv2ide/files/usr;
            // without proot it can't see that dir, so apt ignores our existing apt.conf. Force it to
            // read our config, which redirects every Dir::* path to the real prefix. dpkg also has
            // the stale path compiled in and this old apt doesn't forward --admindir, so tell dpkg
            // directly where its database lives via DPKG_ADMINDIR.
            put("APT_CONFIG", "${prefix.absolutePath}/etc/apt/apt.conf")
            put("DPKG_ADMINDIR", "${prefix.absolutePath}/var/lib/dpkg")
            put("INPUTRC", "${homeDir().absolutePath}/.codestudio/inputrc")
            put("NODE_OPTIONS", "--openssl-config=${prefix.absolutePath}/etc/tls/openssl.cnf --unhandled-rejections=warn -r ${bionicCompatPath()}")
            // Android system roots — the linker + namespace read these.
            put("ANDROID_ART_ROOT", sysEnv["ANDROID_ART_ROOT"] ?: "/apex/com.android.art")
            put("ANDROID_DATA", sysEnv["ANDROID_DATA"] ?: "/data")
            put("ANDROID_I18N_ROOT", sysEnv["ANDROID_I18N_ROOT"] ?: "/apex/com.android.i18n")
            put("ANDROID_ROOT", sysEnv["ANDROID_ROOT"] ?: "/system")
            put("ANDROID_RUNTIME_ROOT", sysEnv["ANDROID_RUNTIME_ROOT"] ?: "/apex/com.android.runtime")
            put("ANDROID_TZDATA_ROOT", sysEnv["ANDROID_TZDATA_ROOT"] ?: "/apex/com.android.tzdata")
            put("BOOTCLASSPATH", sysEnv["BOOTCLASSPATH"] ?: "")
            put("DEX2OATBOOTCLASSPATH", sysEnv["DEX2OATBOOTCLASSPATH"] ?: "")
            // Force termux-exec system_linker_exec regardless of SELinux context evaluation:
            // on Android 10+ (SDK>=29), untrusted apps hit W^X execute_no_trans; the only safe path
            // is /system/bin/linker64 which is always permitted.
            put("ANDROID__BUILD_VERSION_SDK", Build.VERSION.SDK_INT.toString())
            val seContext = runCatching { File("/proc/self/attr/current").readText().trim() }.getOrNull() ?: ""
            if (seContext.isNotEmpty()) put("TERMUX__SE_PROCESS_CONTEXT", seContext)
            put("TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE", "force")
        }
    }

    private fun writeSessionScript() {
        // The session runs the linker directly (see startSession); this file is a no-op kept so the
        // "scripts/" dir exists and future shells have a stable entry point.
        File(scriptsDir(), "session-host.sh").apply {
            writeText("#!/system/bin/sh\n")
            runCatching { Os.chmod(absolutePath, 0x1ED) }.onFailure { setExecutable(true, false) }
        }
    }

    /**
     * Give interactive bash a real prompt. Without PS1 bash shows its bare default (`bash-5.3$`).
     * We render the root-style prompt users know from the old proot shell; the shell still runs as
     * the app uid (no proot => no real root), so the trailing character is `$`, not `#`.
     */
    private fun writeShellConfig(prefix: File) {
        val marker = "# cs-terminal-prompt"
        val ps1Line =
            "export PS1='\\[\\e[1;32m\\]root\\[\\e[0m\\]@\\[\\e[1;36m\\]localhost\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]\\[\\e[1;37m\\]\\$\\[\\e[0m\\] '"
        // We launch bash as a LOGIN shell (-l), which reads ~/.profile but NOT ~/.bashrc. Termux's
        // and opencode's setup both live in ~/.bashrc (PS1, PATH exports, aliases), so make ~/.profile
        // source it; otherwise login shells ignore those settings and we'd keep seeing `bash-5.3$`.
        val profileMarker = "# cs-terminal-profile"
        val profileLine = "[ -f \"\$HOME/.bashrc\" ] && . \"\$HOME/.bashrc\" 2>/dev/null"
        val target = listOf(
            File(prefix, "etc/bash.bashrc"),
            File(homeDir(), ".bashrc"),
        )
        for (f in target) {
            runCatching {
                if (!f.exists()) runCatching { f.parentFile?.mkdirs() }
                if (!f.exists() || !f.readText().contains(marker)) {
                    f.appendText("\n$marker\n$ps1Line\n")
                    Log.i(TAG, "PS1 configurado en ${f.absolutePath}")
                }
            }.onFailure { Log.w(TAG, "writeShellConfig ${f.absolutePath}: ${it.message}") }
        }
        val profile = File(homeDir(), ".profile")
        runCatching {
            if (!profile.exists()) profile.parentFile?.mkdirs()
            if (!profile.exists() || !profile.readText().contains(profileMarker)) {
                profile.appendText("\n$profileMarker\n$profileLine\n")
                Log.i(TAG, "profile carga .bashrc en ${profile.absolutePath}")
            }
        }.onFailure { Log.w(TAG, "writeShellConfig profile: ${it.message}") }
        writeInputrc()
    }

    /**
     * The AAIDE bootstrap's terminfo is incomplete: readline doesn't recognize ESC[H / ESC[F / ESC[5~ /
     * ESC[6~ and instead ECHOES the leftover bytes (you see `~~~` for PGUP/PGDN) and ignores the
     * arrows' cursor-motion. An explicit inputrc bypasses terminfo — every function key is bound to
     * its readline function directly. Pointed to via INPUTRC in buildEnvironment().
     */
    private fun writeInputrc() {
        val f = File(homeDir(), ".codestudio/inputrc")
        val marker = "# cs-terminal-inputrc"
        val content = """
            $marker
            set editing-mode emacs
            set keymap emacs
            set bell-style none
            set completion-ignore-case on
            set show-all-if-ambiguous on
            set blink-matching-paren on
            set enable-bracketed-paste off
            ${'$'}if term=xterm-256color
            "\e[H": beginning-of-line
            "\e[OH": beginning-of-line
            "\e[1~": beginning-of-line
            "\e[F": end-of-line
            "\e[OF": end-of-line
            "\e[4~": end-of-line
            "\e[A": previous-history
            "\e[B": next-history
            "\e[C": forward-char
            "\e[D": backward-char
            "\e[5~": beginning-of-history
            "\e[6~": end-of-history
            "\e[3~": delete-char
            "\e[2~": quoted-insert
            "\e[Z": complete
            ${'$'}endif
        """.trimIndent() + "\n"
        runCatching {
            if (!f.exists() || !f.readText().contains(marker)) {
                f.parentFile?.mkdirs()
                f.writeText(content)
                Log.i(TAG, "inputrc escrito en ${f.absolutePath}")
            }
        }.onFailure { Log.w(TAG, "writeInputrc: ${it.message}") }
    }

    /**
     * The AAIDE bootstrap's `pkg` is a baked-in script with `/data/data/com.tom.rv2ide` paths
     * inside; the iterative scrub corruptions are unrecoverable. Replace it with a clean wrapper
     * that just delegates to apt (both use the same subcommand names: update, install, …).
     * Written on every startSession to heal the damage done to the original file.
     */
    private fun writePkgWrapper(prefix: File) {
        val pkg = File(prefix, "bin/pkg")
        val p = prefix.absolutePath
        runCatching {
            pkg.writeText(
                """
                |#!$p/bin/bash
                |# CodeStudio pkg → apt wrapper (replaces corrupted AAIDE pkg script)
                |export TERMUX_APP_PACKAGE_MANAGER=apt
                |exec "$p/bin/apt" "${'$'}@"
                """.trimMargin().replace("\n|", "\n") + "\n",
            )
            ensureExecutable(pkg)
            Log.i(TAG, "pkg wrapper escrito en ${pkg.absolutePath}")
        }.onFailure { Log.w(TAG, "writePkgWrapper: ${it.message}") }
    }

    /**
     * apt spawns dpkg helpers (dpkg-deb, dpkg-split, sh, tar …) from a *minimal* PATH that
     * doesn't include $PREFIX/bin or /system/bin. Wrap dpkg with a tiny shell script that
     * ensures those dirs are on PATH first, then execs the real dpkg binary. Also satisfies
     * our `Dir::Bin::dpkg` pointing at `$PREFIX/bin/_cs-dpkg`.
     */
    private fun writeDpkgWrapper(prefix: File) {
        val wrapper = File(prefix, "bin/_cs-dpkg")
        val heal = File(prefix, "bin/_cs-heal").absolutePath
        val realDpkg = File(prefix, "bin/dpkg").absolutePath
        val p = prefix.absolutePath
        writeHealScript(prefix)
        runCatching {
            wrapper.writeText(
                """
                |#!$p/bin/bash
                |case ":${'$'}PATH:" in *":${'$'}PREFIX/bin:"*) ;; *) export PATH="$p/bin:$p/bin/applets:/system/bin:/sbin:/bin:${'$'}PATH" ;; esac
                |"$heal" "$p" >/dev/null 2>&1 || true
                |exec "$realDpkg" "${'$'}@"
                """.trimMargin().replace("\n|", "\n") + "\n",
            )
            ensureExecutable(wrapper)
            Log.i(TAG, "_cs-dpkg wrapper escrito en ${wrapper.absolutePath}")
        }.onFailure { Log.w(TAG, "writeDpkgWrapper: ${it.message}") }
    }

    /**
     * Write the shell counterpart of healTermuxState so every dpkg invocation runs the heal
     * *again* — apt/dpkg install fresh maintainer scripts mid-transaction that startSession
     * hasn't seen yet, and each new-termux deb bakes /data/data/com.termux/… paths that would
     * otherwise trip seccomp (signal 31) or break the trusted.gpg.d keyring symlinks.
     */
    private fun writeHealScript(prefix: File) {
        val heal = File(prefix, "bin/_cs-heal")
        val p = prefix.absolutePath
        runCatching {
            heal.writeText(
                """
                |#!$p/bin/bash
                |P="${'$'}{1:-$p}"
                |for f in "${'$'}P"/var/lib/dpkg/info/*.prerm "${'$'}P"/var/lib/dpkg/info/*.preinst "${'$'}P"/var/lib/dpkg/info/*.postrm "${'$'}P"/var/lib/dpkg/info/*.postinst "${'$'}P"/var/lib/dpkg/info/*.triggers; do
                |  [ -f "${'$'}f" ] || continue
                |  sed -i 's#/data/data/com\.tom\.rv2ide/files/usr#'"${'$'}P"'#g; s#/data/data/com\.termux/files/usr#'"${'$'}P"'#g' "${'$'}f" 2>/dev/null
                |done
                |S="${'$'}P/var/lib/dpkg/status"
                |[ -f "${'$'}S" ] && sed -i 's/^Version: v1\.0-1$/Version: 1.0-1/' "${'$'}S" 2>/dev/null
                |D="${'$'}P/etc/apt/trusted.gpg.d"; K="${'$'}P/share/termux-keyring"
                |for g in "${'$'}D"/*.gpg; do
                |  [ -f "${'$'}g" ] && [ -s "${'$'}g" ] && continue
                |  rm -f "${'$'}g" 2>/dev/null
                |  [ -f "${'$'}K/${'$'}{g##*/}" ] && ln -s "${'$'}K/${'$'}{g##*/}" "${'$'}g" 2>/dev/null
                |done
                |exit 0
                """.trimMargin().replace("\n|", "\n") + "\n",
            )
            ensureExecutable(heal)
            Log.i(TAG, "_cs-heal escrito en ${heal.absolutePath}")
        }.onFailure { Log.w(TAG, "writeHealScript: ${it.message}") }
    }

    override fun stopSession() {
        session?.finishIfRunning()
        session = null
        _running.value = false
    }

    override fun writeCommand(line: String) {
        session?.write(line + "\n")
    }

    // ── Command execution against the Termux userland ─────────────────────
    // Runs <prefix>/bin/sh (through the system linker — see class KDoc) with the full userland
    // environment. Commands are written to a per-invocation script file so quotes can't break argv.
    fun runInPrefix(command: String, onOutput: ((String) -> Unit)? = null, cwd: File? = null): Int {
        val scriptFile = File(scriptsDir(), "cmd-${System.nanoTime()}.sh")
        scriptFile.writeText("#!/system/bin/sh\n$command\n")
        ensureExecutable(scriptFile)
        val pb = ProcessBuilder(linker(), File(prefixDir(), "bin/sh").absolutePath, scriptFile.absolutePath)
        pb.environment().clear()
        pb.environment().putAll(buildEnvironment())
        pb.directory((cwd ?: homeDir()))
        pb.redirectErrorStream(true)
        return try {
            val proc = pb.start()
            proc.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    Log.d(TAG, line)
                    onOutput?.invoke(line)
                }
            }
            proc.waitFor()
        } finally {
            scriptFile.delete()
        }
    }

    /** Kept for callers that conceptually distinguish the two execution arms; identical to [runInPrefix]. */
    fun runWithProot(command: String, onOutput: ((String) -> Unit)? = null, cwd: File? = null): Int =
        runInPrefix(command, onOutput, cwd)

    // ── Tooling installs (Node, npm, Python) ──────────────────────────────
    fun isNodeInstalled(): Boolean = File(prefixDir(), "bin/node").exists()
    fun isNpmInstalled(): Boolean = File(prefixDir(), "bin/npm").exists()
    fun isPythonInstalled(): Boolean = File(prefixDir(), "bin/python").exists() || File(prefixDir(), "bin/python3").exists()

    fun installNode(onProgress: (String) -> Unit = {}): Boolean = installDebPackages(
        "nodejs-lts npm c-ares libicu libsqlite",
        "Node.js",
        onProgress,
    )

    fun installPython(onProgress: (String) -> Unit = {}): Boolean = installDebPackages(
        "python python-pip",
        "Python",
        onProgress,
    )

    private fun installDebPackages(packages: String, label: String, onProgress: (String) -> Unit): Boolean {
        val prefix = prefixDir()
        onProgress("Descargando $label…")
        val dl = runInPrefix(
            "cd $prefix/tmp && apt-get update --allow-insecure-repositories 2>&1; " +
                "apt-get download --allow-unauthenticated $packages 2>&1",
            onOutput = { onProgress(it) },
        )
        if (dl != 0) { onProgress("apt-get download ($label) salió $dl"); return false }
        onProgress("Extrayendo $label…")
        val extract = """
            |cd ${prefix.absolutePath}/tmp &&
            |mkdir -p _stage &&
            |for deb in *.deb; do
            |  [ -f "${'$'}deb" ] && dpkg-deb -x "${'$'}deb" _stage/ 2>&1
            |done &&
            |if [ -d "_stage${'$'}PREFIX" ]; then cp -a _stage${'$'}PREFIX/* "${'$'}PREFIX/" 2>&1
            |elif [ -d "_stage/usr" ]; then cp -a _stage/usr/* "${'$'}PREFIX/" 2>&1; fi &&
            |chmod 700 "${'$'}PREFIX/bin/"* 2>/dev/null &&
            |rm -rf _stage *.deb && echo OK
        """.trimMargin()
        val rc = runInPrefix(extract, onOutput = { onProgress(it) })
        return rc == 0 && when (label) {
            "Node.js" -> isNodeInstalled() && isNpmInstalled()
            else -> isPythonInstalled()
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────
    private fun java.io.InputStream.rawCopy(out: java.io.OutputStream) {
        val buf = ByteArray(64 * 1024)
        var n: Int
        while (true) {
            n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
    }

    private fun ensureExecutable(file: File) {
        if (!file.exists()) return
        runCatching { Os.chmod(file.absolutePath, 0x1ED) }.onFailure { file.setExecutable(true, false) }
    }

    // TerminalSessionClient — same minimal set as TerminalEngine. The interactive host routes
    // onTextChanged to the attached TerminalView via onScreenChanged so new output repaints
    // immediately (the emulator view redraws only on invalidate()).
    @Volatile
    var onScreenChanged: ((TerminalSession) -> Unit)? = null
    override fun onTextChanged(c: TerminalSession) {
        onScreenChanged?.invoke(c)
    }
    override fun onTitleChanged(c: TerminalSession) {}
    override fun onSessionFinished(f: TerminalSession) { _running.value = false; session = null }
    override fun onCopyTextToClipboard(s: TerminalSession, t: String) {
        val ctx = appContext ?: return
        runCatching {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(null, t))
            Toast.makeText(ctx, "Copiado", Toast.LENGTH_SHORT).show()
        }.onFailure { Log.e(TAG, "clipboard copy failed", it) }
    }
    override fun onPasteTextFromClipboard(s: TerminalSession?) {
        val ctx = appContext ?: return
        val target = s ?: session ?: return
        runCatching {
            val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val item = clip.primaryClip?.getItemAt(0) ?: return
            val text = item.coerceToText(ctx).toString()
            if (text.isEmpty()) return
            target.getEmulator()?.paste(text) ?: target.write(text)
        }.onFailure { Log.e(TAG, "clipboard paste failed", it) }
    }
    override fun onBell(s: TerminalSession) {}
    override fun onColorsChanged(s: TerminalSession) {}
    override fun onTerminalCursorStateChange(b: Boolean) {}
    override fun setTerminalShellPid(s: TerminalSession, pid: Int) { Log.i(TAG, "shell pid $pid") }
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(t: String, m: String) { Log.e(TAG, "$t: $m") }
    override fun logWarn(t: String, m: String) { Log.w(TAG, "$t: $m") }
    override fun logInfo(t: String, m: String) { Log.i(TAG, "$t: $m") }
    override fun logDebug(t: String, m: String) { Log.d(TAG, "$t: $m") }
    override fun logVerbose(t: String, m: String) { Log.v(TAG, "$t: $m") }
    override fun logStackTraceWithMessage(t: String, m: String, e: Exception) { Log.e(t, m, e) }
    override fun logStackTrace(t: String, e: Exception) { Log.e(t, "", e) }
}