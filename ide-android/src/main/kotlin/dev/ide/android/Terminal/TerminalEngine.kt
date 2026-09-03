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
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object TerminalEngine : TerminalSessionClient {

    private const val TAG = "TerminalEngine"

    private val TOOLKIT_ASSETS = listOf("libtalloc.so.2","liblzma.so.5","libandroid-shmem.so")
    // Termux userland bootstrap — same rootfs Termux the app installs, so `pkg`, `$PREFIX/bin`,
    // and `/etc/profile` conventions all match. Pinned to a specific release (the version stamp in
    // the path is updated periodically by Termux; bump here when Termux bumps it). The bootstrap
    // zip is ~30 MB vs Ubuntu's ~76 MB and runs entirely under proot, so the SELinux `untrusted_app`
    // cannot-exec-on-app_data_file restriction never applies to its child processes.
    // The Termux bootstrap zip is published under the name `bootstrap-aarch64.zip` (not `arm64-v8a`).
    // Wrong name → 404, the engine sits on "Downloading…" forever. Version stamp is updated whenever
    // Termux rebuilds; bump both URL and ROOTFS_VERSION together when that happens.
    private const val ROOTFS_VERSION = "bootstrap-2026.08.30-r1%2Bapt.android-7"
    private const val ROOTFS_URL = "https://github.com/termux/termux-packages/releases/download/$ROOTFS_VERSION/bootstrap-aarch64.zip"
    private const val ROOTFS_FILE = "bootstrap-aarch64.zip"
    // Marker written under the rootfs after a successful extract. Re-runs skip re-downloading when
    // the marker + the bash sentinel both exist. Versioned so a future Termux bootstrap layout change
    // (e.g. $PREFIX moves) invalidates the cache without manual cleanup.
    private const val ROOTFS_MARKER = ".cs-termux-exec-v1"

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
            if (!File(rootfs, "bin/bash").exists() || !File(rootfs, ROOTFS_MARKER).exists()) {
                _setup.value = SetupState.Downloading("Downloading Termux bootstrap (~30 MB)…")
                report("Downloading Termux bootstrap…")
                val archive = downloadToCache(ROOTFS_URL, ROOTFS_FILE, report)
                _setup.value = SetupState.Extracting
                report("Extracting Termux bootstrap…")
                if (rootfs.exists()) rootfs.deleteRecursively()
                rootfs.mkdirs()
                extractZip(archive, rootfs)
                File(rootfs, ROOTFS_MARKER).writeText("ok\n")
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
        val homeDir = File(filesDir!!, "home").apply { mkdirs() }
        // Termux's bootstrap zip has a FLAT layout — `bin/`, `lib/`, `etc/`, `share/` sit at the
        // root of the zip, NOT under `usr/`. PREFIX therefore IS the rootfs directory, the same
        // way it is in real Termux (`/data/data/com.termux/files/usr`).
        val prootPath = proot.absolutePath
        // Proot argv: see https://github.com/termux/proot for the option set. `--link2symlink` is
        // mandatory on Android (no real symlink support across the bind mounts); `-L` follows
        // absolute symlinks; `-p` pretends to be root inside the chroot; `--kill-on-exit` cleans up
        // the child if proot dies. We exec Termux's bash directly (`/bin/bash -l`) rather than
        // `/usr/bin/login` so the env we pass below is the only env the shell sees.
        val args = arrayOf(prootPath,"--kill-on-exit","--link2symlink","-L","-p","-r",rootfs.absolutePath,"-b","/storage/emulated/0:/sdcard","-b","/proc","-b","/sys","-b","/dev","/bin/bash","-l")
        // Termux-style env: PREFIX points at the rootfs itself; PATH starts with $PREFIX/bin so
        // `bash`, `pkg`, `apt-get` etc. resolve; we then expose Android's `/system/bin` so `am`,
        // `pm`, `cmd` are reachable from inside the shell. HOME is a per-app `home/` that proot
        // sees as `/home` once the rootfs has been chrooted into.
        val env = arrayOf(
            "HOME=${homeDir.absolutePath}",
            "PREFIX=${rootfs.absolutePath}",
            "TERM=xterm-256color",
            "PATH=${rootfs.absolutePath}/bin:/system/bin",
            "LD_LIBRARY_PATH=${support.absolutePath}",
            "PROOT_TMP_DIR=${hostTmp.absolutePath}",
            "TMPDIR=${hostTmp.absolutePath}",
            "PROOT_LOADER=${loader.absolutePath}",
        )
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

    /**
     * Pure-JDK extractor for the Termux bootstrap zip (no commons-compress on the classpath).
     * The zip has a FLAT layout — `bin/`, `lib/`, `etc/`, `share/`, `var/`, `include/` sit at the
     * root of the zip (NOT under `usr/`). proot refuses to run a binary whose exec bit got lost
     * in flight.
     *
     * Android's `java.util.zip.ZipEntry` stub jar hides `getExternalAttributes()` (real Android
     * also strips it at runtime on API levels <26), so we can't read the zip's recorded mode bits.
     * Instead we chmod +x everything under `bin/`, `lib/`, and `libexec/` — these are the only
     * paths the Termux bootstrap ships executables in. Everything else (config, docs, share data)
     * is left without +x, matching the upstream zip's intent.
     *
     * Symlinks are NOT stored as zip symlink entries in the Termux bootstrap — instead the zip
     * ships a top-level `SYMLINKS.txt` manifest (one entry per line, `<target>←<source>`
     * separated by U+2190). We process it once at the end so e.g. `bin/pkg` resolves through
     * its SONAME-style `pkg←./bin/termux-tools` link chain, and `libssl.so.3` finds `libssl.so`.
     * Lines starting with `/data/data/com.termux/...` are absolute Termux-app paths and have
     * nothing to do with our chroot — skip them.
     */
    private fun extractZip(archive: File, dest: File) {
        ZipInputStream(archive.inputStream().buffered()).use { zis ->
            val buf = ByteArray(64 * 1024)
            var entry = zis.nextEntry
            var symlinksManifest: ByteArray? = null
            while (entry != null) {
                if (entry.name == "SYMLINKS.txt") {
                    val baos = java.io.ByteArrayOutputStream()
                    while (true) { val n = zis.read(buf); if (n < 0) break; baos.write(buf, 0, n) }
                    symlinksManifest = baos.toByteArray()
                } else if (!entry.isDirectory) {
                    val out = File(dest, entry.name)
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos ->
                        while (true) {
                            val n = zis.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                        }
                    }
                    if (entry.name.startsWith("bin/") || entry.name.startsWith("lib/") || entry.name.startsWith("libexec/")) {
                        try { Os.chmod(out.absolutePath, 0x1ED) } catch (_: Exception) { out.setExecutable(true, false) }
                    }
                }
                entry = zis.nextEntry
            }
            symlinksManifest?.let { applySymlinksManifest(it, dest) }
        }
    }

    /**
     * Parse Termux's `SYMLINKS.txt` (UTF-8, one `<target>←<source>` per line, U+2190 separator)
     * and create each relative symlink under [dest]. Lines whose target starts with `/data/data/`
     * (real Termux app paths) are skipped — they don't apply to our chroot. A missing source is
     * skipped, not fatal: termux-keyring links reference files outside the bootstrap.
     *
     * Android's `java.io.File` stub jar does NOT include `isSymbolicLink()` (it's only on the
     * real filesystem at runtime, not on the compile-time stubs). Instead of pre-checking, we
     * unconditionally `Files.deleteIfExists` the target before calling `createSymbolicLink` —
     * `deleteIfExists` is in `java.nio.file` which IS stubbed on Android, so it compiles cleanly.
     */
    private fun applySymlinksManifest(bytes: ByteArray, dest: File) {
        val sep = "←".toByteArray(Charsets.UTF_8)
        val text = String(bytes, Charsets.UTF_8)
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val arrowIdx = indexOfUtf8(line, sep)
            if (arrowIdx < 0) continue
            val target = line.substring(0, arrowIdx).trim()
            val source = line.substring(arrowIdx + sep.size).trim()
            if (target.startsWith("/data/data/") || target.startsWith("/")) continue
            val linkFile = File(dest, target)
            val relSource = File(source)
            val resolvedSource = if (relSource.isAbsolute) relSource else File(linkFile.parentFile ?: dest, relSource.path)
            if (!resolvedSource.exists()) continue
            linkFile.parentFile?.mkdirs()
            try {
                java.nio.file.Files.deleteIfExists(linkFile.toPath())
                java.nio.file.Files.createSymbolicLink(linkFile.toPath(), resolvedSource.toPath())
            } catch (_: Exception) { /* UnsupportedOperationException on filesystems that disallow symlinks — fall through. */ }
        }
    }

    /** UTF-8 byte index of [needle] in [haystack], or -1 if absent. Cheaper than `String.indexOf` on a CharSequence because we don't decode. */
    private fun indexOfUtf8(haystack: String, needle: ByteArray): Int {
        }
    }

    /** UTF-8 byte index of [needle] in [haystack], or -1 if absent. Cheaper than `String.indexOf` on a CharSequence because we don't decode. */
    private fun indexOfUtf8(haystack: String, needle: ByteArray): Int {
        val h = haystack.toByteArray(Charsets.UTF_8)
        outer@ for (i in 0..(h.size - needle.size)) {
            for (j in needle.indices) if (h[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
