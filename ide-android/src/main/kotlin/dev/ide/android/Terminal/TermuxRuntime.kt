package dev.ide.android.Terminal

import android.content.Context
import android.system.Os
import android.util.Log
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
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * On-device Termux-userland engine — the "bootstrap + shell" runtime that the vendored
 * `:termux:application`'s `libtermux-bootstrap.so` already ships inside THIS apk.
 *
 * ## Why everything runs under proot
 *
 * The bootstrap's ELF binaries (interpreter `/system/bin/linker64`, NEEDED libs all Termux-built
 * and sitting in `<filesDir>/usr/lib`) cannot be exec'd directly from app-private storage on
 * modern Android: SELinux + the scoped-linker namespaces reject execve of a bionic-linked ELF
 * that resolves its dependencies outside `nativeLibraryDir`/`/system`. The observed failure is
 * `exec(.../usr/bin/bash): Permission denied`. [TerminalEngine] hit the same wall and its
 * working answer doubles as ours — run every binary through the bundled proot with **loader
 * injection** (`-L`, [TerminalEngine.prootExec]/loader), which rewrites the ELF interpreter and
 * mmaps the guest binary instead of relying on kernel execve. That is exactly how the Alpine
 * rootfs already boots in this app, so the Termux prefix reuses the identical, proven chain:
 *
 *   1. `/system/bin/sh` (bionic) is the *first* process — resolves natively.
 *   2. it execs `$PROOT $PROOT_ARGS <prefix>/bin/bash -l` (argv assembled by [sessionEnv] via
 *      [prootArgsSuffix], mirroring init-host.sh's bind set plus
 *      `-b <prefix>:/data/data/com.termux/files/usr`. That canonical remap is what lets tools with
 *      the compiled-in `/data/data/com.termux/files/usr` path (dpkg maintainer scripts, apt-get,
 *      pip) see a real prefix.
 *   3. proot's tracer follows every child exec (`sh`→dash, perl, node, python…), loader-loading
 *      each Termux binary, so the whole userland works without a single successful kernel execve.
 *
 * ## Extraction
 *
 * The bootstrap zip embedded in `libtermux-bootstrap.so` (JNI `TermuxInstaller.getZip()`) is
 * extracted to `<filesDir>/usr`, with SYMLINKS.txt (`src←dst`, U+2190) recreated via `Os.symlink`
 * exactly like the vendored `TermuxInstaller`. Same mechanism OpenClaw's Android runtime uses to
 * run Node.js / Python on-device.
 */
object TermuxRuntime : TerminalSessionClient, TerminalRuntime {

    private const val TAG = "TermuxRuntime"
    private const val BOOTSTRAP_MARKER = ".cs-termux-bootstrap-v1"
    private const val TERMUX_CANONICAL_PREFIX = "/data/data/com.termux/files/usr"

    private val _setup = MutableStateFlow<TerminalSetupState>(TerminalSetupState.Idle)
    override val setup: StateFlow<TerminalSetupState> = _setup
    private val _running = MutableStateFlow(false)
    override val running: StateFlow<Boolean> = _running

    private var filesDir: File? = null
    private var appContext: Context? = null
    private var nativeLibDir: String? = null
    override var session: TerminalSession? = null

    override fun init(context: Context) {
        appContext = context.applicationContext
        filesDir = context.applicationContext.filesDir
        nativeLibDir = context.applicationContext.applicationInfo.nativeLibraryDir
        Log.i(TAG, "init; nativeLibDir=$nativeLibDir filesDir=${filesDir?.absolutePath}")
    }

    private fun prefixDir() = File(filesDir!!, "usr")
    private fun homeDir() = File(filesDir!!, "home").apply { mkdirs() }
    private fun tmpDir() = File(filesDir!!, "tmp").apply { mkdirs() }
    private fun scriptsDir() = File(filesDir!!, "scripts").apply { mkdirs() }
    private fun prootExec() = File(nativeLibDir!!, "libproot.so")
    private fun prootLoader() = File(nativeLibDir!!, "libloader.so")

    override suspend fun ensureReady(onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (_setup.value is TerminalSetupState.Ready) return@withContext
        if (_setup.value is TerminalSetupState.Downloading || _setup.value is TerminalSetupState.Extracting) return@withContext
        try {
            val prefix = prefixDir()
            _setup.value = TerminalSetupState.Downloading("Bootstrap Termux (~30 MB embebido)…")
            extractBootstrapOnce(prefix, onProgress)
            installBionicCompat()
            writeSessionScript()
            ensureExecutable(prootExec())
            ensureExecutable(prootLoader())
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
    // First process is /system/bin/sh (bionic — resolves natively, exactly like TerminalEngine),
    // then the host script execs proot with [prootArgsSuffix] and hands bash over. See the class
    // KDoc for why every Termux binary must be loader-loaded through proot.
    override fun startSession(cols: Int, rows: Int) {
        if (session != null) return
        if (_setup.value !is TerminalSetupState.Ready) {
            Log.w(TAG, "startSession before Ready; ignoring")
            return
        }
        val prefix = prefixDir()
        val host = File(scriptsDir(), "session-host.sh")
        val shell = "/system/bin/sh"
        val args = arrayOf("-c", host.absolutePath)
        val env = sessionEnv()
        val s = TerminalSession(shell, prefix.absolutePath, args, env, rows, this)
        session = s
        s.updateSize(cols, rows)
        _running.value = true
        Log.i(TAG, "Termux session started: shell=$shell host=${host.absolutePath} prefix=${prefix.absolutePath}")
    }

    /** Env handed to the session host script — base userland env + the proot hand-off variables. */
    private fun sessionEnv(): Array<String> {
        val env = buildEnvironment().toMutableMap()
        val proot = prootExec()
        val loader = prootLoader()
        env["PROOT"] = proot.absolutePath
        env["PROOT_LOADER"] = loader.absolutePath
        env["PROOT_ARGS"] = prootArgsSuffix(canonicalRemap = true, workdir = homeDir().absolutePath)
        return env.map { (k, v) -> "$k=$v" }.toTypedArray()
    }

    private fun buildEnvironment(): Map<String, String> {
        val prefix = prefixDir()
        val sysEnv = System.getenv()
        return buildMap {
            put("PREFIX", prefix.absolutePath)
            put("TERMUX_PREFIX", prefix.absolutePath)
            put("TERMUX__PREFIX", prefix.absolutePath)
            put("HOME", homeDir().absolutePath)
            put("PATH", "${prefix.absolutePath}/bin:${prefix.absolutePath}/bin/applets:/system/bin:/sbin:/bin")
            put("LD_LIBRARY_PATH", "${prefix.absolutePath}/lib")
            put("TERM", "xterm-256color")
            put("LANG", "C.UTF-8")
            put("COLORTERM", "truecolor")
            put("TMPDIR", tmpDir().absolutePath)
            put("TMP", tmpDir().absolutePath)
            put("TEMP", tmpDir().absolutePath)
            put("PROOT_TMP_DIR", tmpDir().absolutePath)
            put("SSL_CERT_FILE", "${prefix.absolutePath}/etc/tls/cert.pem")
            put("SSL_CERT_DIR", "/system/etc/security/cacerts")
            put("CURL_CA_BUNDLE", "${prefix.absolutePath}/etc/tls/cert.pem")
            put("GIT_SSL_CAINFO", "${prefix.absolutePath}/etc/tls/cert.pem")
            put("OPENSSL_CONF", "${prefix.absolutePath}/etc/tls/openssl.cnf")
            put("NODE_OPTIONS", "--openssl-config=${prefix.absolutePath}/etc/tls/openssl.cnf --unhandled-rejections=warn -r ${bionicCompatPath()}")
            // Android system roots — the loader + linker namespace read these.
            put("ANDROID_ART_ROOT", sysEnv["ANDROID_ART_ROOT"] ?: "/apex/com.android.art")
            put("ANDROID_DATA", sysEnv["ANDROID_DATA"] ?: "/data")
            put("ANDROID_I18N_ROOT", sysEnv["ANDROID_I18N_ROOT"] ?: "/apex/com.android.i18n")
            put("ANDROID_ROOT", sysEnv["ANDROID_ROOT"] ?: "/system")
            put("ANDROID_RUNTIME_ROOT", sysEnv["ANDROID_RUNTIME_ROOT"] ?: "/apex/com.android.runtime")
            put("ANDROID_TZDATA_ROOT", sysEnv["ANDROID_TZDATA_ROOT"] ?: "/apex/com.android.tzdata")
            put("BOOTCLASSPATH", sysEnv["BOOTCLASSPATH"] ?: "")
            put("DEX2OATBOOTCLASSPATH", sysEnv["DEX2OATBOOTCLASSPATH"] ?: "")
            put("LINKER", if (File("/system/bin/linker64").exists()) "/system/bin/linker64" else "/system/bin/linker")
        }
    }

    /**
     * proot argv suffix mirroring init-host.sh's bind set. With [canonicalRemap] the Termux prefix
     * is also bound onto the compiled-in `/data/data/com.termux/files/usr` so maintainer scripts and
     * tools with hardcoded paths resolve. Built at runtime (on-device paths) so /apex and friends
     * are those the process actually sees. `-L` forces loader injection — the mechanism that makes
     * exec of Termux binaries possible at all (see class KDoc).
     */
    fun prootArgsSuffix(canonicalRemap: Boolean = true, workdir: String = homeDir().absolutePath): String {
        val prefix = prefixDir()
        val binds = mutableListOf<String>()
        for (m in listOf(
            "/apex", "/odm", "/product", "/system", "/system_ext", "/vendor",
            "/linkerconfig/ld.config.txt", "/linkerconfig/com.android.art/ld.config.txt",
            "/plat_property_contexts", "/property_contexts",
        )) {
            val f = File(m)
            if (f.exists()) {
                binds += "-b ${runCatching { f.canonicalPath }.getOrElse { f.absolutePath }}"
            }
        }
        binds += "-b /sdcard"
        binds += "-b /storage"
        binds += "-b /data"
        binds += "-b /proc"
        binds += "-b /sys"
        binds += "-b /dev"
        binds += "-b /dev/urandom:/dev/random"
        binds += "-b ${prefix.absolutePath}"
        if (canonicalRemap) binds += "-b ${prefix.absolutePath}:$TERMUX_CANONICAL_PREFIX"
        val fdDir = File("/proc/self/fd")
        if (fdDir.exists()) {
            binds += "-b /proc/self/fd:/dev/fd"
            val labels = arrayOf("stdin", "stdout", "stderr")
            for (n in 0..2) {
                if (File("/proc/self/fd/$n").exists()) {
                    binds += "-b /proc/self/fd/$n:/dev/${labels[n]}"
                }
            }
        }
        binds += "-b ${tmpDir().absolutePath}:/dev/shm"
        return "--kill-on-exit -w $workdir ${binds.joinToString(" ")} -0 --link2symlink --sysvipc -L"
    }

    // Builds the session host script (bionic sh → proot → Termux bash). Recreated on every start
    // keeps paths current if filesDir moves; harmless because it only writes text.
    private fun writeSessionScript() {
        val prefix = prefixDir()
        val script = """
            |#!/system/bin/sh
            |export PREFIX='${prefix.absolutePath}'
            |export TERMUX_PREFIX="${'$'}PREFIX"
            |export TERMUX__PREFIX="${'$'}PREFIX"
            |export HOME='${homeDir().absolutePath}'
            |export TMPDIR='${tmpDir().absolutePath}'
            |export LD_LIBRARY_PATH="${'$'}PREFIX/lib"
            |export PATH="${'$'}PREFIX/bin:${'$'}PREFIX/bin/applets:/system/bin:/sbin:/bin"
            |export TERM=xterm-256color
            |export LANG=C.UTF-8
            |[ -d "${'$'}HOME" ] || mkdir -p "${'$'}HOME"
            |[ -d "${'$'}TMPDIR" ] || mkdir -p "${'$'}TMPDIR"
            |exec "${'$'}PROOT" ${'$'}PROOT_ARGS "${'$'}PREFIX/bin/bash" -l
        """.trimMargin()
        File(scriptsDir(), "session-host.sh").apply {
            writeText(script)
            runCatching { Os.chmod(absolutePath, 0x1ED) }.onFailure { setExecutable(true, false) }
        }
        // PROOT / PROOT_LOADER / PROOT_ARGS come from the session env handed by startSession.
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
    // Everything runs through proot (loader injection), never direct exec — see class KDoc. The
    // runner shell is /system/bin/sh; the Termux tools the command invokes are its children and
    // get loader-loaded by proot's tracer. Commands are written to a per-invocation script file
    // (not inlined) so quotes inside the command can't break the /system/bin/sh -c parsing.
    fun runInPrefix(command: String, onOutput: ((String) -> Unit)? = null, cwd: File? = null): Int {
        val scriptFile = File(scriptsDir(), "cmd-${System.nanoTime()}.sh")
        scriptFile.writeText("#!/system/bin/sh\n$command\n")
        ensureExecutable(scriptFile)
        val env = buildEnvironment().toMutableMap()
        val proot = prootExec()
        env["PROOT"] = proot.absolutePath
        env["PROOT_LOADER"] = prootLoader().absolutePath
        env["PROOT_ARGS"] = prootArgsSuffix(canonicalRemap = true, workdir = (cwd ?: homeDir()).absolutePath)
        env["CS_SCRIPT"] = scriptFile.absolutePath
        val host = "exec \"\$PROOT\" \$PROOT_ARGS /system/bin/sh \"\$CS_SCRIPT\""
        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", host)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.directory((cwd ?: homeDir()))
            pb.redirectErrorStream(true)
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

    /** Same as [runInPrefix] — kept for callers that conceptually want "proot explicitly". */
    fun runWithProot(command: String, onOutput: ((String) -> Unit)? = null, cwd: File? = null): Int =
        runInPrefix(command, onOutput, cwd)

    // ── Tooling installs (Node, npm, Python, proot) ───────────────────────
    fun isNodeInstalled(): Boolean = File(prefixDir(), "bin/node").exists()
    fun isNpmInstalled(): Boolean = File(prefixDir(), "bin/npm").exists()
    fun isPythonInstalled(): Boolean = File(prefixDir(), "bin/python").exists() || File(prefixDir(), "bin/python3").exists()
    fun isProotInstalled(): Boolean = File(prefixDir(), "bin/proot").exists()

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

    fun installProot(onProgress: (String) -> Unit = {}): Boolean = installDebPackages(
        "proot libtalloc",
        "proot",
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
            "Python" -> isPythonInstalled()
            else -> isProotInstalled()
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

    // TerminalSessionClient — same minimal set as TerminalEngine.
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
    override fun logError(t: String, m: String) { Log.e(TAG, "$t: $m") }
    override fun logWarn(t: String, m: String) { Log.w(TAG, "$t: $m") }
    override fun logInfo(t: String, m: String) { Log.i(TAG, "$t: $m") }
    override fun logDebug(t: String, m: String) { Log.d(TAG, "$t: $m") }
    override fun logVerbose(t: String, m: String) { Log.v(TAG, "$t: $m") }
    override fun logStackTraceWithMessage(t: String, m: String, e: Exception) { Log.e(t, m, e) }
    override fun logStackTrace(t: String, e: Exception) { Log.e(t, "", e) }
}