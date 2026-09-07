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
import java.util.zip.ZipInputStream

/**
 * On-device Termux-userland engine — the "bootstrap + shell" runtime shipped by the vendored
 * `:termux:application`'s `libtermux-bootstrap.so` (JNI `TermuxInstaller.getZip()`).
 *
 * ## Why `/system/bin/linker64` + `libtermux-exec.so` instead of proot
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
 * (which untrusted_app IS allowed to execute), so the call succeeds. `libtermux-exec-so` is a
 * `LD_PRELOAD` shim whose overridden `exec(3)` family (execve/execvp/execvpe) rewrites every exec
 * of a Termux binary into `/system/bin/linker64 <bin> …` transparently. We therefore:
 *
 *   1. Launch the *first* process as `/system/bin/linker64 <prefix>/bin/sh` (or bash) with
 *      `LD_PRELOAD=<prefix>/lib/libtermux-exec.so`, `LD_LIBRARY_PATH=<prefix>/lib:/system/lib64`.
 *      The linker is a permitted exec target, so it never hits the SELinux wall.
 *   2. Every child it spawns inherits the same env, so `libtermux-exec` keeps intercepting their
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
 * `usr/lib/libtermux-exec.so` (the standard name Termux uses for the preload shim).
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
    private fun linker() = if (File("/system/bin/linker64").exists()) "/system/bin/linker64" else "/system/bin/linker"

    override suspend fun ensureReady(onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (_setup.value is TerminalSetupState.Ready) return@withContext
        if (_setup.value is TerminalSetupState.Downloading || _setup.value is TerminalSetupState.Extracting) return@withContext
        try {
            val prefix = prefixDir()
            _setup.value = TerminalSetupState.Downloading("Bootstrap Termux (~30 MB embebido)…")
            extractBootstrapOnce(prefix, onProgress)
            installTermuxExec(prefix)
            writeAptConfig(prefix)
            installBionicCompat()
            writeSessionScript()
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
     */
    private fun installTermuxExec(prefix: File) {
        val target = termuxExec()
        if (target.exists()) return
        val src = File(prefix, "lib/libtermux-exec-direct-ld-preload.so")
        if (src.exists()) {
            src.copyTo(target, overwrite = true)
        }
        ensureExecutable(target)
        Log.i(TAG, "termux-exec instalado en $target")
    }

    /**
     * apt-get/dpkg on stock Termux assume `/data/data/com.termux/files/usr`. Without proot we can't
     * bind-mount our real prefix onto that path, so instead mirror OpenClaw's fix: an `etc/apt/apt.conf`
     * with `Dir "/"` and every path pointed at our real prefix.
     */
    private fun writeAptConfig(prefix: File) {
        val aptConf = File(prefix, "etc/apt/apt.conf")
        val p = prefix.absolutePath
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
            Dir::Bin::dpkg "$p/bin/dpkg";
            Dir::Bin::Methods "$p/lib/apt/methods/";
            Dir::Bin::apt-key "$p/bin/apt-key";
            Dpkg::Options:: "--force-configure-any";
            Dpkg::Options:: "--force-bad-path";
            Dpkg::Options:: "--instdir=$p";
            Acquire::AllowInsecureRepositories "true";
            """.trimIndent() + "\n",
        )
        Log.i(TAG, "apt.conf escrito en $aptConf")
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
    // libtermux-exec (LD_PRELOAD, inherited by every child) rewrites subsequent execs to linker64.
    override fun startSession(cols: Int, rows: Int) {
        if (session != null) return
        if (_setup.value !is TerminalSetupState.Ready) {
            Log.w(TAG, "startSession before Ready; ignoring")
            return
        }
        val prefix = prefixDir()
        // /data/data/<pkg>/files/... has SELinux `app_data_file` context — W^X blocks direct
        // kernel execve. We go through /system/bin/linker64 (a `system_linker_exec` target) which
        // loads the ELF with its own privileges; the user's shell needs no separate execute bit on
        // app_data_file. Use `bin/sh` (dash) as the entry: bash drags in libreadline/libiconv/
        // libandroid-support, and on devices where any of those failed to extract (or where the
        // bootstrap zip was packaged with mismatched soname hashes) the chain breaks at
        // `exec(.../usr/bin/bash): Permission denied`. dash has none of those NEEDED entries —
        // it loads from /system/lib via the dynamic linker and starts reliably.
        val sh = File(prefix, "bin/sh").absolutePath
        val args = arrayOf(sh, "-l")
        val env = buildEnvironment().map { (k, v) -> "$k=$v" }.toTypedArray()
        val s = TerminalSession(linker(), prefix.absolutePath, args, env, rows, this)
        session = s
        s.updateSize(cols, rows)
        _running.value = true
        Log.i(TAG, "Termux session started: linker=${linker()} sh=$sh prefix=${prefix.absolutePath}")
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
            put("LD_LIBRARY_PATH", "${prefix.absolutePath}/lib:/system/lib64:/system/lib")
            put("LD_PRELOAD", termuxExec().absolutePath)
            put("TERM", "xterm-256color")
            put("LANG", "C.UTF-8")
            put("COLORTERM", "truecolor")
            put("TMPDIR", tmpDir().absolutePath)
            put("TMP", tmpDir().absolutePath)
            put("TEMP", tmpDir().absolutePath)
            put("SSL_CERT_FILE", "${prefix.absolutePath}/etc/tls/cert.pem")
            put("SSL_CERT_DIR", "/system/etc/security/cacerts")
            put("CURL_CA_BUNDLE", "${prefix.absolutePath}/etc/tls/cert.pem")
            put("GIT_SSL_CAINFO", "${prefix.absolutePath}/etc/tls/cert.pem")
            put("OPENSSL_CONF", "${prefix.absolutePath}/etc/tls/openssl.cnf")
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