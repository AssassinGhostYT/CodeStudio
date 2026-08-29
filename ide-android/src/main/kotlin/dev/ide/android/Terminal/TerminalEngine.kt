package dev.ide.android.Terminal

import android.content.Context
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The PRoot-backed Linux terminal engine.
 *
 * The proot toolkit (proot + libtalloc/liblzma/libandroid-shmem, ~450 KB in total) ships INSIDE the
 * APK under `assets/terminal/` — nothing to fetch, no dead URLs. Only the Ubuntu rootfs is
 * downloaded, on FIRST use (a ~76 MB tarball that would blow up the APK). The toolkit lives under
 * `<filesDir>/support/` and the rootfs under `<filesDir>/rootfs/`. Once present, tapping the
 * terminal icon launches the session with the canonical PRoot invocation — `--kill-on-exit
 * --link2symlink --sysvipc -L -p -r rootfs --change-id=0:0 --cwd=/root` with `/sdcard`, `/proc`,
 * `/sys` and `/dev` bound in.
 */
object TerminalEngine {

    /** Files shipped inside the APK under `assets/terminal/`, copied to `support/` on first use. */
    private val TOOLKIT_ASSETS = listOf(
        "proot",
        "libtalloc.so.2",
        "liblzma.so.5",
        "libandroid-shmem.so",
    )

    private const val ROOTFS_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz"

    private const val ROOTFS_FILE = "ubuntu-rootfs.tar.gz"

    sealed interface SetupState {
        data object Idle : SetupState
        data class Downloading(val label: String) : SetupState
        data object Extracting : SetupState
        data object Ready : SetupState
        data class Failed(val message: String) : SetupState
    }

    private val _setup = MutableStateFlow<SetupState>(SetupState.Idle)
    val setup: StateFlow<SetupState> = _setup

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output

    private var filesDir: File? = null
    private var appContext: Context? = null
    private var process: Process? = null
    private val pool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "terminal-io").apply { isDaemon = true }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        filesDir = context.applicationContext.filesDir
    }

    private fun supportDir(): File = File(filesDir ?: error("TerminalEngine.init() not called"), "support").apply { mkdirs() }
    private fun rootfsDir(): File = File(filesDir ?: error("TerminalEngine.init() not called"), "rootfs")

    /** Idempotent asset bootstrap: downloads anything missing, then prepares the rootfs. */
    suspend fun ensureReady(onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        if (_setup.value is SetupState.Ready) return@withContext
        if (_setup.value is SetupState.Downloading || _setup.value is SetupState.Extracting) return@withContext
        try {
            val rootfs = rootfsDir()
            ensureToolkit()
            val report: (String) -> Unit = { msg ->
                // Surface percent progress through the observable state, not just the callback.
                if (msg.startsWith("Downloading ") && msg.contains('%')) {
                    _setup.value = SetupState.Downloading(msg)
                }
                onProgress(msg)
            }
            if (!File(rootfs, "/bin/bash").exists()) {
                _setup.value = SetupState.Downloading("Downloading rootfs (~76 MB)…")
                report("Downloading rootfs…")
                val archive = downloadToCache(ROOTFS_URL, ROOTFS_FILE, report)
                _setup.value = SetupState.Extracting
                report("Extracting rootfs…")
                rootfs.mkdirs()
                TarGz.extract(archive, rootfs)
                archive.delete()
            }
            TOOLKIT_ASSETS.forEach { chmodExecutable(File(supportDir(), it)) }
            _setup.value = SetupState.Ready
        } catch (e: Exception) {
            _setup.value = SetupState.Failed(e.message ?: e::class.simpleName ?: "Setup failed")
        }
    }

    /** Copies the APK-bundled proot + libs into `support/` if (any of them) is missing. */
    private fun ensureToolkit() {
        val context = appContext ?: return // assets not reachable before init() — still fine
        val support = supportDir()
        if (TOOLKIT_ASSETS.all { name -> File(support, name).exists() && File(support, name).length() > 0 }) return
        _setup.value = SetupState.Extracting
        for (name in TOOLKIT_ASSETS) {
            val target = File(support, name)
            if (target.exists() && target.length() > 0) continue
            context.assets.open("terminal/$name").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
    }

    /** Also re-applies the exec bit on every start, since data dirs can lose modes across sessions. */
    fun startSession() {
        if (process?.isAlive == true) return
        val rootfs = rootfsDir()
        val support = supportDir()
        TOOLKIT_ASSETS.forEach { chmodExecutable(File(support, it)) }
        _output.value = ""
        _running.value = true
        val pb = ProcessBuilder(
            prootExecutable().absolutePath,
            "--kill-on-exit", "--link2symlink", "--sysvipc",
            "-L", "-p",
            "-r", rootfs.absolutePath,
            "--change-id=0:0",
            "--cwd=/root",
            "-b", "/storage/emulated/0:/sdcard",
            "-b", "/proc", "-b", "/sys", "-b", "/dev",
            "/bin/bash", "-l",
        ).directory(rootfs)
        pb.environment()["LD_LIBRARY_PATH"] = support.absolutePath
        pb.environment()["HOME"] = File(rootfs, "root").absolutePath
        pb.environment()["TERM"] = "xterm-256color"
        pb.environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        try {
            val p = pb.start()
            process = p
            val input = p.inputStream
            val err = p.errorStream
            pool.execute {
                val buf = ByteArray(4096)
                val out = StringBuilder()
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.append(String(buf, 0, n, Charsets.UTF_8))
                    if (out.length > 8192) { _output.value += out; out.setLength(0) }
                }
                _output.value += out
            }
            pool.execute {
                val buf = ByteArray(4096)
                val out = StringBuilder()
                while (true) {
                    val n = err.read(buf)
                    if (n < 0) break
                    out.append(String(buf, 0, n, Charsets.UTF_8))
                    if (out.length > 8192) { _output.value += out; out.setLength(0) }
                }
                _output.value += out
            }
            pool.execute {
                p.waitFor()
                _running.value = false
            }
        } catch (e: Exception) {
            _output.value += "error: ${e.message}\n"
            _running.value = false
        }
    }

    /** Sends a command line (with newline) to the session's stdin. */
    fun writeCommand(line: String) {
        val p = process
        if (p == null || !p.isAlive) {
            _output.value += "\$ $line\n"
            return
        }
        try {
            p.outputStream.write((line + "\n").toByteArray(Charsets.UTF_8))
            p.outputStream.flush()
        } catch (_: Exception) {
        }
    }

    fun stopSession() {
        process?.destroy()
        _running.value = false
    }

    private fun prootExecutable(): File = File(supportDir(), "proot")

    /** Applies rwx for the owner and r-x for group/others via the real syscall — `File.setExecutable`
     *  only toggles the owner bit on webview-ish/Android storage and can silently no-op. */
    private fun chmodExecutable(file: File): File {
        if (!file.exists()) return file
        return try {
            Os.chmod(file.absolutePath, 0x1ED) // 0755: owner rwx, group/others r-x
            file
        } catch (_: Exception) {
            // Fallback for non-Linux test hosts: just try the JVM API as well, best-effort.
            file.setExecutable(true, false)
            file
        }
    }

    private fun downloadToCache(url: String, name: String, onProgress: (String) -> Unit): File {
        val cacheDir = File(filesDir ?: error("TerminalEngine.init() not called"), "cache").apply { mkdirs() }
        val target = File(cacheDir, name)
        if (target.exists() && target.length() > 0) return target
        // Write to a temp file and rename only on success, so an interrupted download is never
        // mistaken for a complete archive on the next attempt.
        val part = File(cacheDir, "$name.part")
        try {
            streamTo(url, part, onProgress)
            if (!part.renameTo(target)) part.copyTo(target, overwrite = true).also { part.delete() }
            return target
        } finally {
            part.delete()
        }
    }

    private fun streamTo(url: String, target: File, onProgress: (String) -> Unit) {
        if (target.exists() && target.length() > 0) return
        target.parentFile?.mkdirs()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "CodeStudio-Terminal/1.0")
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            BufferedOutputStream(FileOutputStream(target)).use { output ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (total > 0 && n > 0) onProgress("Downloading ${target.name} ${done * 100 / total}%")
                }
            }
        }
    }
}