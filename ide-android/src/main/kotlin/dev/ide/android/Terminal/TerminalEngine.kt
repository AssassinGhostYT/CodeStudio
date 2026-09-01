package dev.ide.android.Terminal

import android.content.Context
import android.system.Os
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.net.HttpURLConnection
import java.net.URL

object TerminalEngine : TerminalSessionClient {

    private const val TAG = "TerminalEngine"

    private val TOOLKIT_ASSETS = listOf("libtalloc.so.2","liblzma.so.5","libandroid-shmem.so")
    private const val ROOTFS_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz"
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

    private fun supportDir() = File(filesDir!!, "support").apply { mkdirs() }
    private fun rootfsDir() = File(filesDir!!, "rootfs")
    private fun prootExec() = appContext?.let { File(it.applicationInfo.nativeLibraryDir, "libproot.so") } ?: File(supportDir(), "proot")
    private fun nativeDir(name: String) = appContext?.let { File(it.applicationInfo.nativeLibraryDir, name) } ?: File("")

    suspend fun ensureReady(onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        if (_setup.value is SetupState.Ready) return@withContext
        if (_setup.value is SetupState.Downloading || _setup.value is SetupState.Extracting) return@withContext
        try {
            val rootfs = rootfsDir()
            ensureToolkit()
            val report: (String) -> Unit = { msg ->
                if (msg.startsWith("Downloading ") && msg.contains('%')) _setup.value = SetupState.Downloading(msg)
                onProgress(msg)
            }
            if (!File(rootfs, "bin/bash").exists() || !File(rootfs, ".cs-exec-v1").exists()) {
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
            _setup.value = if (bad.isEmpty()) SetupState.Ready else SetupState.Failed(bad.joinToString("; "))
        } catch (e: Exception) {
            _setup.value = SetupState.Failed(e.message ?: "failed")
        }
    }

    private fun ensureToolkit() {
        val ctx = appContext ?: return
        val support = supportDir()
        if (TOOLKIT_ASSETS.all { File(support, it).exists() && File(support, it).length() > 0 }) return
        _setup.value = SetupState.Extracting
        for (name in TOOLKIT_ASSETS) {
            val t = File(support, name)
            if (t.exists() && t.length() > 0) continue
            ctx.assets.open("terminal/$name").use { inp -> t.outputStream().use { inp.copyTo(it) } }
        }
    }

    fun startSession(cols: Int = 80, rows: Int = 24) {
        if (session != null) return
        val rootfs = rootfsDir()
        val support = supportDir()
        val proot = prootExec()
        try { Os.chmod(proot.absolutePath, 0x1ED) } catch (_: Exception) { proot.setExecutable(true) }
        val loader = nativeDir("libproot_loader.so")
        try { Os.chmod(loader.absolutePath, 0x1ED) } catch (_: Exception) { loader.setExecutable(true) }
        val hostTmp = File(filesDir!!, "tmp").apply { mkdirs() }
        val prootPath = proot.absolutePath
        val args = arrayOf(prootPath,"--kill-on-exit","--link2symlink","--sysvipc","-L","-p","-r",rootfs.absolutePath,"--change-id=0:0","--cwd=/root","-b","/storage/emulated/0:/sdcard","-b","/proc","-b","/sys","-b","/dev","/bin/bash","-l")
        val env = arrayOf("LD_LIBRARY_PATH=${support.absolutePath}","HOME=/root","TERM=xterm-256color","PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin","PROOT_TMP_DIR=${hostTmp.absolutePath}","TMPDIR=/tmp","PROOT_LOADER=${loader.absolutePath}")
        val s = TerminalSession(prootPath, rootfs.absolutePath, args, env, 5000, this)
        session = s
        s.updateSize(cols, rows)
        _running.value = true
        Log.i(TAG, "termux session started")
    }

    fun stopSession() { session?.finishIfRunning(); session = null; _running.value = false }
    fun writeCommand(line: String) { session?.write(line + "\n") }

    override fun onTextChanged(c: TerminalSession) {}
    override fun onTitleChanged(c: TerminalSession) {}
    override fun onSessionFinished(f: TerminalSession) { _running.value = false; session = null }
    override fun onCopyTextToClipboard(s: TerminalSession, t: String) {}
    override fun onPasteTextFromClipboard(s: TerminalSession?) {}
    override fun onBell(s: TerminalSession) {}
    override fun onColorsChanged(s: TerminalSession) {}
    override fun onTerminalCursorStateChange(b: Boolean) {}
    override fun setTerminalShellPid(s: TerminalSession, pid: Int) { Log.i(TAG, "shell pid $pid") }
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(t: String, m: String) { Log.e(TAG, m) }
    override fun logWarn(t: String, m: String) { Log.w(TAG, m) }
    override fun logInfo(t: String, m: String) { Log.i(TAG, m) }
    override fun logDebug(t: String, m: String) { Log.d(TAG, m) }
    override fun logVerbose(t: String, m: String) { Log.v(TAG, m) }
    override fun logStackTraceWithMessage(t: String, m: String, e: Exception) { Log.e(t, m, e) }
    override fun logStackTrace(t: String, e: Exception) { Log.e(t, "", e) }

    private fun ensureExecutable(file: File): String? {
        if (!file.exists()) return "${file.name}: missing"
        try { Os.chmod(file.absolutePath, 0x1ED) } catch (_: Exception) { file.setExecutable(true, false) }
        if (file.canExecute()) return null
        try {
            val ctx = appContext; val n = file.name; file.parentFile?.mkdirs()
            if (ctx != null) ctx.assets.open("terminal/$n").use { inp -> file.outputStream().use { inp.copyTo(it) } }
            try { Os.chmod(file.absolutePath, 0x1ED) } catch (_: Exception) { file.setExecutable(true, false) }
        } catch (_: Exception) {}
        return if (file.canExecute()) null else "${file.name}: not executable"
    }

    private fun downloadToCache(url: String, name: String, onProgress: (String) -> Unit): File {
        val cache = File(filesDir!!, "cache").apply { mkdirs() }
        val target = File(cache, name)
        if (target.exists() && target.length() > 0) return target
        val part = File(cache, "$name.part")
        try {
            streamTo(url, part, onProgress)
            if (!part.renameTo(target)) part.copyTo(target, overwrite = true).also { part.delete() }
            return target
        } finally { part.delete() }
    }

    private fun streamTo(url: String, target: File, onProgress: (String) -> Unit) {
        if (target.exists() && target.length() > 0) return
        target.parentFile?.mkdirs()
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true; c.connectTimeout = 15000; c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "CodeStudio-Terminal/1.0")
        val tot = c.contentLengthLong
        c.inputStream.use { inp -> BufferedOutputStream(FileOutputStream(target)).use { out ->
            val buf = ByteArray(64*1024); var d = 0L
            while (true) { val n = inp.read(buf); if (n<0) break; out.write(buf,0,n); d+=n; if (tot>0) onProgress("Downloading ${d*100/tot}%") }
        }}
    }
}
