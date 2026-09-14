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
            installBionicCompat()
            writeSessionScript()
            writeShellConfig(prefix)
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
            Dir::Bin::dpkg "$p/bin/dpkg";
            Dir::Bin::Methods "$p/lib/apt/methods/";
            Dir::Bin::apt-key "$p/bin/apt-key";
            Dpkg::Options:: "--force-configure-any";
            Dpkg::Options:: "--force-bad-path";
            Dpkg::Options:: "--instdir=$p";
            Acquire::AllowInsecureRepositories "true";
            // The ACS repo signs InRelease with a key not present in this bootstrap's keyring; the
            // lists fetch fine (hash-verified) so don't block installs on our hobby mirror's key.
            Acquire::AllowUnauthenticated "true";
            Acquire::https::CaInfo "${systemCaBundle().absolutePath}";
            """.trimIndent() + "\n",
        )
        Log.i(TAG, "apt.conf escrito en $aptConf")
    }

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