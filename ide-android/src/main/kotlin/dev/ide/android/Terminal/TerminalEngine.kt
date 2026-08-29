package dev.ide.android.Terminal

import android.content.Context
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
 * All assets are downloaded on FIRST use (nothing is bundled, keeping the APK small): the proot
 * binary + its runtime libs live under `<filesDir>/support/`, and an Ubuntu rootfs under
 * `<filesDir>/rootfs/`. Once present, tapping the terminal icon launches the session with the
 * canonical PRoot invocation — `--kill-on-exit --link2symlink --sysvipc -L -p -r rootfs
 * --change-id=0:0 --cwd=/root` with `/sdcard`, `/proc`, `/sys` and `/dev` bound in.
 */
object TerminalEngine {

    // Asset sources — swap these for the exact URLs you host the files at (the download layer
    // streams whatever is served, then `support/` files are chmod +x'd).
    private const val PROOT_URL =
        "https://github.com/Termux/termux-packages/releases/download/package-proot/proot_5.4.0_aarch64.deb"
    private const val LIBTALLOC_URL =
        "https://raw.githubusercontent.com/Termux/termux-packages/master/packages/libtalloc/build.sh"
    private const val LIBLZMA_URL =
        "https://raw.githubusercontent.com/Termux/termux-packages/master/packages/liblzma/build.sh"
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
    private var process: Process? = null
    private val pool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "terminal-io").apply { isDaemon = true }
    }

    fun init(context: Context) {
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
            if (!prootExecutable().exists()) download(PROOT_URL, File(supportDir(), "proot"))
            if (!File(supportDir(), "libtalloc.so.2").exists()) download(LIBTALLOC_URL, File(supportDir(), "libtalloc.so.2"))
            if (!File(supportDir(), "liblzma.so.5").exists()) download(LIBLZMA_URL, File(supportDir(), "liblzma.so.5"))
            if (!File(rootfs, "/bin/bash").exists()) {
                _setup.value = SetupState.Extracting
                onProgress("Extracting rootfs…")
                val archive = downloadToCache(ROOTFS_URL, ROOTFS_FILE, onProgress)
                rootfs.mkdirs()
                TarGz.extract(archive, rootfs)
                archive.delete()
            }
            prootExecutable().setExecutable(true, true)
            File(supportDir(), "libtalloc.so.2").setExecutable(true, true)
            File(supportDir(), "liblzma.so.5").setExecutable(true, true)
            _setup.value = SetupState.Ready
        } catch (e: Exception) {
            _setup.value = SetupState.Failed(e.message ?: e::class.simpleName ?: "Setup failed")
        }
    }

    /** Starts an interactive bash under proot, streaming stdout/stderr into [output]. */
    fun startSession() {
        if (process?.isAlive == true) return
        val rootfs = rootfsDir()
        val support = supportDir()
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

    private fun download(url: String, target: File) {
        downloadTo(url, target) { _setup.value = SetupState.Downloading("Downloading ${target.name}") }
    }

    private fun downloadToCache(url: String, name: String, onProgress: (String) -> Unit): File {
        val cacheDir = File(filesDir ?: error("TerminalEngine.init() not called"), "cache").apply { mkdirs() }
        val target = File(cacheDir, name)
        if (target.exists() && target.length() > 0) return target
        downloadTo(url, target, onProgress)
        return target
    }

    private fun downloadTo(url: String, target: File, onProgress: (String) -> Unit) {
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