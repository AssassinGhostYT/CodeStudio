package dev.ide.android.Terminal

import android.content.Context
import android.os.Os
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

object TerminalEngine : TerminalSessionClient {

    private const val TAG = "TerminalEngine"

    private val TOOLKIT_ASSETS = listOf(
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
    var session: TerminalSession? = null
        private set

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
            if (!File(rootfs, "/bin/bash").exists() || !File(rootfs, ".cs-exec-v1").exists()) {
                _setup.value = SetupState.Downloading("Downloading rootfs (~76 MB)…")
                report("Downloading rootfs…")
                val archive = downloadToCache(ROOTFS_URL, ROOTFS_FILE, report)
                _setup.value = SetupState.Extracting
                report("Extracting rootfs…")
                if (rootfs.exists()) rootfs.deleteRecursively()
                rootfs.mkdirs()
                TarGz.extract(archive, rootfs)
                File(rootfs, ".cs-exec-v1").writeText("exec-bits-preserved\n")
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

    fun startSession(columns: Int = 80, rows: Int = 24) {
        if (session != null) return
        val rootfs = rootfsDir()
        val support = supportDir()
        val proot = prootExecutable()
        ensureExecutable(proot)

        val loader = nativeDir("libproot_loader.so")
        chmodExecutable(loader)

        val hostTmp = File(filesDir ?: return, "tmp").apply { mkdirs() }

        val prootPath = proot.absolutePath
        val prootArgs = arrayOf(
            prootPath,
            "--kill-on-exit", "--link2symlink", "--sysvipc",
            "-L", "-p",
            "-r", rootfs.absolutePath,
            "--change-id=0:0",
            "--cwd=/root",
            "-b", "/storage/emulated/0:/sdcard",
            "-b", "/proc", "-b", "/sys", "-b", "/dev",
            "/bin/bash", "-l",
        )

        val env = arrayOf(
            "LD_LIBRARY_PATH=${support.absolutePath}",
            "HOME=/root",
            "TERM=xterm-256color",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "PROOT_TMP_DIR=${hostTmp.absolutePath}",
            "TMPDIR=/tmp",
            "PROOT_LOADER=${loader.absolutePath}",
        )

        val s = TerminalSession(prootPath, rootfs.absolutePath, prootArgs, env, 5000, this)
        session = s
        s.updateSize(columns, rows)
        _output.value = ""
        _running.value = true
    }

    fun stopSession() {
        session?.finishIfRunning()
        session = null
        _running.value = false
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        val emulator = changedSession.getEmulator()
        _output.value = emulator?.screen?.let { screen ->
            val rows = screen.mRows
            val cols = screen.mColumns
            val sb = StringBuilder()
            for (row in 0 until rows) {
                val line = screen.getLine(row) ?: continue
                sb.append(line.toString().trimEnd())
                if (row < rows - 1) sb.append('\n')
            }
            sb.toString()
        } ?: ""
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}
    
    override fun onSessionFinished(finishedSession: TerminalSession) {
        _running.value = false
        session = null
    }
    
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
    override fun onPasteTextFromClipboard(session: TerminalSession?) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
        Log.i(TAG, "Shell PID: $pid")
    }
    
    override fun getTerminalCursorStyle(): Int? = null
    
    override fun logError(tag: String, message: String) { Log.e(TAG, message) }
    override fun logWarn(tag: String, message: String) { Log.w(TAG, message) }
    override fun logInfo(tag: String, message: String) { Log.i(TAG, message) }
    override fun logDebug(tag: String, message: String) { Log.d(TAG, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(TAG, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "", e) }

    private fun prootExecutable(): File =
        appContext?.let { File(it.applicationInfo.nativeLibraryDir, "libproot.so") } ?: File(supportDir(), "proot")

    private fun nativeDir(name: String): File = appContext?.let {
        File(it.applicationInfo.nativeLibraryDir, name)
    } ?: File("")

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