package dev.ide.android.Terminal

import android.content.Context
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object TerminalEngine {

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

    suspend fun ensureReady(onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        if (_setup.value is SetupState.Ready) return@withContext
        if (_setup.value is SetupState.Downloading || _setup.value is SetupState.Extracting) return@withContext
        try {
            val rootfs = rootfsDir()
            ensureToolkit()
            val report: (String) -> Unit = { msg ->
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
            val bad = TOOLKIT_ASSETS.mapNotNull { ensureExecutable(File(supportDir(), it)) }
            _setup.value = if (bad.isEmpty()) SetupState.Ready
                           else SetupState.Failed(bad.joinToString("; ") + " " + diagnostics(prootExecutable()))
        } catch (e: Exception) {
            _setup.value = SetupState.Failed(e.message ?: e::class.simpleName ?: "Setup failed")
        }
    }

    private fun ensureToolkit() {
        val context = appContext ?: return
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

    fun startSession() {
        if (process?.isAlive == true) return
        val rootfs = rootfsDir()
        val support = supportDir()
        val proot = prootExecutable()
        ensureExecutable(proot)

        val context = appContext
        val altDir = context?.let { ctx ->
            val dir = File(ctx.codeCacheDir, "toolkit").apply { mkdirs() }
            
            TOOLKIT_ASSETS.forEach { name ->
                val target = File(dir, name)
                if (!target.exists() || target.length() == 0L) {
                    try {
                        ctx.assets.open("terminal/$name").use { input ->
                            target.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        target.setExecutable(true)
                    } catch (e: Exception) {
                    }
                }
            }
            
            dir
        }
        val altProot = altDir?.let { File(it, "proot") }

        val args = listOf(
            "--kill-on-exit", "--link2symlink", "--sysvipc",
            "-L", "-p",
            "-r", rootfs.absolutePath,
            "--change-id=0:0",
            "--cwd=/root",
            "-b", "/storage/emulated/0:/sdcard",
            "-b", "/proc", "-b", "/sys", "-b", "/dev",
            "/bin/bash", "-l",
        )
        val libPath = listOfNotNull(support.absolutePath, altDir?.absolutePath).distinct().joinToString(":")
        var usedVia = "none"
        var lastErr: Exception? = null

        fun tryLaunch(cmd: List<String>, via: String): Process? {
            val pb = ProcessBuilder(cmd)
            pb.directory(rootfs)
            pb.environment()["LD_LIBRARY_PATH"] = libPath
            pb.environment()["HOME"] = File(rootfs, "root").absolutePath
            pb.environment()["TERM"] = "xterm-256color"
            pb.environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            // This proot is a Termux build: its compiled-in temp dir is
            // /data/data/com.termux/files/usr/tmp, which doesn't exist here. PROOT_TMP_DIR is a HOST
            // path (proot builds the glue rootfs & f2fs probe there), so it must live inside the app
            // sandbox — Android's /tmp isn't writable by apps. TMPDIR stays as the guest /tmp.
            val hostTmp = File(filesDir ?: return null, "tmp").apply { mkdirs() }
            pb.environment()["PROOT_TMP_DIR"] = hostTmp.absolutePath
            pb.environment()["TMPDIR"] = "/tmp"
            return try {
                val p = pb.start()
                usedVia = via
                p
            } catch (e: Exception) {
                lastErr = e
                null
            }
        }

        var p = tryLaunch(listOf(proot.absolutePath) + args, "direct")
        if (p == null && altProot != null && altProot.exists()) {
            p = tryLaunch(listOf(altProot.absolutePath) + args, "codecache")
        }
        if (p == null && altProot != null && altProot.exists()) {
            val linker = if (File("/system/bin/linker64").exists()) "/system/bin/linker64" else "/system/bin/linker"
            p = tryLaunch(listOf(linker, altProot.absolutePath) + args, "linker64")
        }

        _output.value = ""
        if (p == null) {
            _output.value = "error: ${lastErr?.message} via=none probe=${probeExec()} [d] ${diagnostics(proot)}\n"
            _running.value = false
            return
        }
        process = p
        _running.value = true
        if (usedVia != "direct") _output.value = "[launch: $usedVia]\n"
        
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
    }

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

    private fun ensureExecutable(file: File): String? {
        if (!file.exists()) return "${file.name}: missing"
        chmodExecutable(file)
        if (file.canExecute()) return null
        try {
            val context = appContext
            val name = file.name
            file.parentFile?.mkdirs()
            if (context != null) {
                context.assets.open("terminal/$name").use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
            }
            chmodExecutable(file)
        } catch (_: Exception) {
        }
        return if (file.canExecute()) null else "${file.name}: not executable (mode=0${modeString(file)})"
    }

    private fun chmodExecutable(file: File) {
        if (!file.exists()) return
        try {
            Os.chmod(file.absolutePath, 0x1ED)
        } catch (_: Exception) {
            file.setExecutable(true, false)
        }
        try {
            file.setExecutable(true, false)
        } catch (_: Exception) {
        }
    }

    private fun modeString(file: File): String = try {
        Integer.toOctalString(Os.stat(file.absolutePath).st_mode and 0xFFF)
    } catch (_: Exception) {
        "?"
    }

    private fun diagnostics(file: File): String {
        val sb = StringBuilder(file.name).append(" canExecute=").append(file.canExecute())
            .append(" mode=0").append(modeString(file))
            .append(" ctx=").append(selinuxCtx())
            .append(" seccomp=").append(seccompMode())
        try {
            val path = file.absolutePath
            BufferedReader(FileReader("/proc/self/mounts")).useLines { lines ->
                val best = lines.map { it.split(" ") }
                    .filter { path.startsWith(it[1]) }
                    .maxByOrNull { it[1].length }
                if (best != null) sb.append(" mount[").append(best[1]).append("]=").append(best[3])
            }
        } catch (_: Throwable) {
        }
        return sb.toString()
    }

    private fun selinuxCtx(): String = try {
        File("/proc/self/attr/current").readText().trim().ifEmpty { "?" }
    } catch (_: Throwable) {
        "?"
    }

    private fun seccompMode(): String = try {
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("Seccomp:") }?.split(':')
            ?.getOrNull(1)?.trim() ?: "?"
    } catch (_: Throwable) {
        "?"
    }

    private fun probeExec(): String {
        val attempts = listOf(
            listOf("/system/bin/toybox", "true"),
            listOf("/system/bin/sh", "-c", "true"),
        )
        for (cmd in attempts) {
            try {
                val p = ProcessBuilder(cmd).start()
                p.waitFor()
                return "sys-exec(${cmd.first()})=ok"
            } catch (_: Throwable) {
            }
        }
        return "sys-exec=all-fail"
    }

    private fun downloadToCache(url: String, name: String, onProgress: (String) -> Unit): File {
        val cacheDir = File(filesDir ?: error("TerminalEngine.init() not called"), "cache").apply { mkdirs() }
        val target = File(cacheDir, name)
        if (target.exists() && target.length() > 0) return target
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
