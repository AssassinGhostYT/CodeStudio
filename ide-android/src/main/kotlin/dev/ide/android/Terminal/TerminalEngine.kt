package dev.ide.android.Terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * In-IDE proot engine — ReTerminal pattern (https://github.com/RohitKushvaha01/ReTerminal, MIT).
 *
 * The previous Termux-bootstrap approach stalled at "Waiting for shell.." on Android 11+ non-rooted
 * devices: bash's NEEDED entries (`libc`, `libdl`, `libreadline.so.8`, `libiconv.so`, `libandroid-support.so`)
 * are all Termux-built, and the system linker can't resolve them under the scoped-linker namespaces
 * (`/apex/com.android.runtime`, `/linkerconfig/ld.config.txt`) that Android 11+ enforces via SELinux
 * — proot's ptrace-execve returns silently and `TerminalSession` sits on the empty FD forever.
 *
 * ReTerminal sidesteps that with a 3-step orchestration:
 *
 *   1. The *first* shell to run is `/system/bin/sh -c <init-host.sh>` — Android's bionic `sh`,
 *      which resolves natively and never goes through proot. This is the crucial difference vs
 *      the old approach, which called `proot … /bin/bash -l` directly and lost bash at execve.
 *
 *   2. `init-host.sh` (bundled as an APK asset, copied to `<filesDir>/local/bin/` on first run)
 *      then assembles the proot argv with all the bind mounts that Android 11+ requires
 *      (`/apex`, `/product`, `/system_ext`, `/vendor`, `/linkerconfig/ld.config.txt`,
 *      `/property_contexts`, `/dev/urandom:/dev/random`, `/proc/self/fd/{0,1,2}:/dev/{stdin,stdout,stderr}`),
 *      extracts the Alpine rootfs to `<filesDir>/local/alpine/` if not yet present, and `exec`s
 *      `$PROOT $ARGS sh <init>` — Alpine's busybox `ash`, which has no Termux-built deps and
 *      resolves cleanly through the linker.
 *
 *   3. `<init>` (also a bundled asset) sets PATH/PS1/HOME/TERM and execs `/bin/ash` interactively.
 *
 *  Runtime tooling (Node.js, npm, Python) is installed with Alpine's `apk` inside the rootfs — the
 *  same channel Dorpn Editor and other no-Termux Android terminals use. Non-interactive commands
 *  run through [runInPrefix], which shells the same bind map through `run-host.sh` and execs
 *  `/bin/ash -c <command>` inside the chroot.
 *
 * No `libaxs.so` needed (unlike Acode's pattern): the bionic sh hand-off IS the loader alternative.
 * No `LD_PRELOAD=libtermux-exec.so` either — Alpine's libs resolve natively.
 *
 * API surface (unchanged from the previous Termux version — `TerminalPanel.kt` and
 * `TerminalPlugin.kt` keep working without edits):
 *   - `init(context)` — registers the application context (idempotent).
 *   - `ensureReady(onProgress)` — extracts the rootfs on first run, sets up scripts, signals
 *     `TerminalSetupState.Ready`. Skipped if already Ready.
 *   - `setup: StateFlow<SetupState>` — observed by the panel for status display.
 *   - `running: StateFlow<Boolean>` — true while a session is alive.
 *   - `session: TerminalSession?` — the vendored `:termux:emulator`'s `TerminalSession` driving the view.
 *   - `startSession(cols, rows)` — wires up the session after Ready.
 *   - `writeCommand(line)`, `stopSession()` — convenience.
 */
object TerminalEngine : TerminalSessionClient, TerminalRuntime {

    private const val TAG = "TerminalEngine"

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
        nativeLibDir = context.applicationInfo.nativeLibraryDir
        Log.i(TAG, "init; nativeLibDir=$nativeLibDir filesDir=${filesDir?.absolutePath}")
    }

    // Filesystem layout under `<app-data>/local/` (= `$PREFIX/local/`, the directory init-host.sh
    // and run-host.sh address — $PREFIX is the *parent* of files/, so NOT `<app-data>/files/local/`):
    //   local/                     (mirror of Termux's $PREFIX — what init-host.sh expects)
    //     bin/                     init-host.sh, init.sh, rm-wrapper.sh (copied from assets, chmod +x)
    //     lib/                     symlinks: libtalloc.so.2 -> $NATIVE_LIB_DIR/libtalloc.so
    //     alpine/                  extracted Alpine rootfs (bin/, etc/, lib/, usr/, …)
    //       root/                  user home inside the chroot
    //       tmp/                   1777 /tmp, bind-mounted as /dev/shm for proot
    //     stat, vmstat             tiny POSIX shims that procps in Alpine can't synthesize
    //
    // $PREFIX used by init-host.sh = `<filesDir>/parent` (i.e. the directory *above* `files/`),
    // matching ReTerminal MkSession.kt:99 ("PREFIX=${filesDir.parentFile!!.path}"). The extraction,
    // the stat/vmstat shims and every `localDir()`-derived path must live under `$PREFIX/local/…`
    // (NOT `<filesDir>/local/…`) or init-host.sh fails with "no such file or directory" / the
    // ALPINE_ROOTFS_MISSING diagnostic, exactly like the user's device showed.
    private fun prefixDir() = filesDir!!.parentFile!!
    private fun localDir() = File(prefixDir(), "local")
    private fun localBinDir() = File(localDir(), "bin")
    private fun localLibDir() = File(localDir(), "lib")
    private fun alpineDir() = File(localDir(), "alpine")
    private fun alpineTmpDir() = File(alpineDir(), "tmp").apply { mkdirs() }
    private fun alpineRootDir() = File(alpineDir(), "root").apply { mkdirs() }
    private fun tmpDir() = File(filesDir!!, "tmp").apply { mkdirs() }

    // Native binaries — ReTerminal's CMakeLists builds:
    //   libproot.so        the proot executable itself (renamed via set_target_properties OUTPUT_NAME "proot" + PREFIX "lib")
    //   libloader.so       64-bit host loader (build_loader("64", …) → OUTPUT_NAME "loader" + PREFIX "lib")
    //   libloader32.so     optional 32-bit loader (only if HAS_LOADER_32BIT, which depends on the cross target support)
    private fun prootExec() = File(nativeLibDir!!, "libproot.so")
    private fun prootLoader() = File(nativeLibDir!!, "libloader.so")
    private fun prootLoader32() = File(nativeLibDir!!, "libloader32.so").takeIf { it.exists() }
    private fun tallocLib() = File(nativeLibDir!!, "libtalloc.so").takeIf { it.exists() }

    // Choose the right rootfs asset for the running ABI. `Build.SUPPORTED_ABIS[0]` is the device's
    // preferred ABI in priority order; emulator installs typically report only `x86_64`. Falls back
    // to `arm64-v8a` if the runtime reports something exotic — the worst case is a wrong-arch extract
    // (visible immediately as "Exec format error" in the terminal) rather than a silent failure.
    private fun rootfsAssetForDevice(): Pair<String, String> {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in setOf("arm64-v8a", "armeabi-v7a", "x86_64") }
            ?: "arm64-v8a"
        return when (abi) {
            "arm64-v8a" -> "arm64-v8a" to "alpine-aarch64"
            "armeabi-v7a" -> "armeabi-v7a" to "alpine-armhf"
            else -> "x86_64" to "alpine-x86_64"
        }
    }

    // Marker written under $PREFIX/local/alpine/ (= <app-data>/local/alpine/) after a successful
    // extract. Versioned so a
    // future ReTerminal asset layout change (different /etc/profile defaults, different package
    // set) invalidates the cache without manual cleanup. Bumped to v3 from v2: v2 devices could
    // report Ready with a missing interactive `init` script (panel showed exit code 127 +
    // "local/bin/init: No such file or directory"), so v3's ensureReady now also requires the init
    // script to exist before reusing the rootfs and forces a clean re-extract otherwise.
    private const val ROOTFS_MARKER = ".cs-reterminal-v3"

    override suspend fun ensureReady(onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (_setup.value is TerminalSetupState.Ready) return@withContext
        if (_setup.value is TerminalSetupState.Downloading || _setup.value is TerminalSetupState.Extracting) return@withContext
        try {
            val (abiName, rootfsBaseName) = rootfsAssetForDevice()
            val ctx = appContext ?: throw IllegalStateException("TerminalEngine.init(context) not called")
            val alpine = alpineDir()
            _setup.value = TerminalSetupState.Extracting
            onProgress("Preparing Alpine rootfs for $abiName…")

            // 1. Install the init scripts into $PREFIX/local/bin/ from assets if missing.
            //    ReTerminal only writes them once per install — the assets are the source of truth,
            //    and the per-device copies get chmod +x so proot can exec them as part of its argv.
            //
            //    The init-host.sh asset invokes the post-proot entry as `sh $PREFIX/local/bin/init`
            //    (no `.sh` suffix — see init-host.sh line 72), so the asset for the second script is
            //    named `init` in the AAR's per-device copy, even though the asset FILE in the APK
            //    is `init.sh` for clarity. We map asset-name → destination-name explicitly so the
            //    one-to-one mapping in `init-host.sh` works as written.
            localBinDir().mkdirs()
            for ((assetName, destName) in listOf(
                "init-host.sh" to "init-host.sh",
                "init.sh" to "init",
                // Two hardlinks-like aliases: init-host.sh wakes `$BIN/rm` (mounts it over the
                // rootfs bin/rm so proot's host rm is user-safe), and tools.cmd may also reach the
                // wrapper by its bootstrap name.
                "rm-wrapper.sh" to "rm",
                "rm-wrapper.sh" to "rm-wrapper.sh",
                "run-host.sh" to "run-host.sh",
            )) {
                // The interactive `init` entry point is refreshed from the bundled asset every run — it
                // carries the preamble (HOME/cwd, PS1, default env) whose defaults ship with the app; the
                // rest install once so a user's local edits survive app updates. init-host.sh and
                // run-host.sh are ALSO refreshed: they assemble the proot argv and their bind-map relies
                // on files created by the runtime (stat/vmstat), so stale on-device copies from an older
                // build (e.g. pre-fix init-host.sh that tried to tar "$PREFIX/files/alpine.tar.gz") must
                // not win over the shipped version.
                val refresh = destName in setOf("init", "init-host.sh", "run-host.sh")
                installAssetOnce(ctx, assetName, File(localBinDir(), destName), refresh)
            }

            // 2. Extract the Alpine rootfs the first time. Idempotent: if the marker file exists AND
            //    `bin/ash` is in place AND the interactive `init` entry exists, skip the extract. The
            //    marker name is versioned (see above); the extra bin/ash + init checks recover devices
//    that carried a HALF-extracted rootfs or a missing init script from an older build
                //    (the terminal showed "Process completed (code 127)" + "local/bin/init: No such file
                //    or directory") by forcing a clean re-extract.
            val ashExists = File(alpine, "bin/ash").exists()
            val initScript = File(localBinDir(), "init")
            if (File(alpine, ROOTFS_MARKER).exists() && ashExists && initScript.exists()) {
                onProgress("Reusing extracted Alpine rootfs")
            } else {
                _setup.value = TerminalSetupState.Downloading("Extracting Alpine rootfs (~${AssetSizes.rootfs(abiName)} MB)…")
                onProgress("Extracting Alpine rootfs…")
                alpine.deleteRecursively()
                alpine.mkdirs()
                // ReTerminal keeps the extraction under `$PREFIX/local/alpine`, but the rootfs tarball
                // expands to `bin/`, `etc/`, `lib/`, `usr/`, … at the archive root. `tar -xf` with `-C
                // <dest>` is what `init-host.sh` uses, so we mirror that path: open the gzipped tar
                // and stream entries one by one — no shell-out to /system/bin/tar (which would defeat
                // the point of doing this in Kotlin and lock us into whatever busybox Android ships).
                extractGzipTarFromAsset(ctx, "alpine/$rootfsBaseName.tar.gz.rootfs", alpine)
                // Marker written LAST so a crashed extraction doesn't leave a half-state that looks
                // Ready on next launch.
                File(alpine, ROOTFS_MARKER).writeText("ok\n")
                // init-host.sh line 39: it unconditionally removes $PREFIX/libtalloc.so.2 before
                // symlinking — without that link, the system linker can't find talloc when Alpine's
                // dynamically-linked binaries load. The link target is the .so that the ReTerminal
                // CMake build ships inside libproot.so via target_link_libraries; we expose it as
                // a separate file via the consumer-rules path used by Android packaging.
                localLibDir().mkdirs()
                val talloc = tallocLib()
                if (talloc != null) {
                    val link = File(localLibDir(), "libtalloc.so.2")
                    if (!link.exists()) {
                        runCatching { Os.symlink(talloc.absolutePath, link.absolutePath) }
                            .onFailure { link.writeBytes(talloc.readBytes()) }
                    }
                }
            }
            // Ensure the chroot /tmp and /root exist even on the reuse (marker-hit) path — the
            // first launch after moving the runtime to $PREFIX/local must not depend on leftover
            // dirs from an older <filesDir>/local layout.
            alpineTmpDir()
            alpineRootDir()

            // 3. Mark Ready. Set exec bits on the proot chain — Android sometimes drops them at
            //    install time even with useLegacyPackaging=true (the same issue we hit with
            //    libproot_loader.so before). Idempotent.
            //    Also create the /proc/stat + /proc/vmstat bind sources init-host.sh mounts: proot
            //    warns (non-fatally) when they're absent, and Alpine's procps can't synthesize them.
            emptyFile(File(localDir(), "stat"))
            emptyFile(File(localDir(), "vmstat"))
            ensureExecutable(prootExec())
            ensureExecutable(prootLoader())
            prootLoader32()?.let { ensureExecutable(it) }
            tmpDir() // ensure $PROOT_TMP_DIR exists before proot queries it
            _setup.value = TerminalSetupState.Ready
            Log.i(TAG, "ReTerminal-pattern ready; rootfs at ${alpine.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "ensureReady failed", e)
            _setup.value = TerminalSetupState.Failed(e.message ?: "setup failed")
        }
    }

    /**
     * Copy an asset to `$PREFIX/local/bin/<name>` once. Re-running does not overwrite — this is
     * deliberate so a user's local edits (or a future runtime customization) survive across app
     * restarts. The chmod +x happens unconditionally because Android's package installer sometimes
     * strips exec bits on file extraction into filesDir (we hit this with libproot_loader.so before).
     */
    private fun installAssetOnce(ctx: Context, assetName: String, dest: File, refresh: Boolean = false) {
        if (refresh || !dest.exists()) {
            dest.parentFile?.mkdirs()
            ctx.assets.open(assetName).use { input ->
                FileOutputStream(dest).use { input.copyTo(it) }
            }
        }
        try { Os.chmod(dest.absolutePath, 0x1ED) } catch (_: Exception) { dest.setExecutable(true, false) }
    }

    /**
     * Stream-extract a gzipped tarball from APK assets into [dest]. We can't rely on Apache
     * Commons Compress being on the classpath (it isn't — `ide-android` doesn't declare it), and
     * Android's `java.util.zip.GZIPInputStream` + a tiny tar parser is enough: Alpine minirootfs
     * tarballs use GNU/USTAR with no pax extensions, no sparse files, no long-link names.
     *
     * Tar header layout (per POSIX 1003.1-1988 USTAR): 512-byte header, name in [0..100), mode in
     * [100..108), size in [124..136) as a zero-padded octal ASCII string, typeflag at [156], data
     * padded to 512-byte blocks. We only need the mode bits (to preserve the +x on bin/ash,
     * busybox, etc.) and the data; ownership and timestamps are dropped (uid/gid/mtime default to
     * the current process's values, which is what the tar format means by an unrecorded field).
     */
    private fun extractGzipTarFromAsset(ctx: Context, assetName: String, dest: File) {
        val buf = ByteArray(64 * 1024)
        val header = ByteArray(512)
        ctx.assets.open(assetName).use { raw ->
            GZIPInputStream(BufferedInputStream(raw)).use { gz ->
                while (true) {
                    var got = 0
                    while (got < 512) {
                        val n = gz.read(header, got, 512 - got)
                        if (n < 0) return@use  // EOF — tar terminator is two zero blocks; this is the first
                        got += n
                    }
                    if (header.all { it == 0.toByte() }) return@use
                    val name = readCString(header, 0, 100).takeIf { it.isNotEmpty() } ?: continue
                    val mode = readOctal(header, 100, 8)
                    val size = readOctal(header, 124, 12)
                    val typeFlag = header[156].toInt().toChar()
                    if (typeFlag == 'L' || typeFlag == 'K') {
                        // PAX long-link extensions — should not appear in Alpine minirootfs but skip
                        // gracefully if they do (skip the data block too).
                        skipBytes(gz, size)
                        continue
                    }
                    if (typeFlag == '5' || name.endsWith("/")) {
                        File(dest, name).mkdirs()
                        skipBytes(gz, size)
                        continue
                    }
                    val out = File(dest, name)
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos ->
                        var remaining = size
                        while (remaining > 0L) {
                            val chunk = minOf(remaining, buf.size.toLong()).toInt()
                            val n = gz.read(buf, 0, chunk)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                    // Mode bits: only the lower 9 (rwx for owner/group/other) are meaningful; tar
                    // records more (setuid/setgid/sticky) but Android's storage layer doesn't honor
                    // them anyway. AND with 0x1FF (rwx for owner/group/other = 0777) then apply.
                    val execBits = (mode.toInt() and 511)
                    if (execBits != 0) {
                        try { Os.chmod(out.absolutePath, execBits) } catch (_: Exception) { out.setExecutable(true, false) }
                    }
                    // Round up to a 512-byte block boundary — tar always pads data records.
                    val padded = ((size + 511) / 512) * 512
                    skipBytes(gz, padded - size)
                }
            }
        }
    }

    private fun readCString(buf: ByteArray, offset: Int, maxLen: Int): String {
        val end = (offset until offset + maxLen).firstOrNull { buf[it] == 0.toByte() } ?: (offset + maxLen)
        return String(buf, offset, end - offset, Charsets.US_ASCII).trim()
    }

    private fun readOctal(buf: ByteArray, offset: Int, len: Int): Long {
        var result = 0L
        for (i in 0 until len) {
            val c = buf[offset + i].toInt().toChar()
            if (c == ' ' || c == ' ') continue
            if (c !in '0'..'7') break
            result = result * 8 + (c - '0')
        }
        return result
    }

    private fun skipBytes(gz: java.io.InputStream, count: Long) {
        var remaining = count
        val sink = ByteArray(4096)
        while (remaining > 0L) {
            val want = minOf(remaining, sink.size.toLong()).toInt()
            val n = gz.read(sink, 0, want)
            if (n < 0) break
            remaining -= n
        }
    }

    private fun ensureExecutable(file: File) {
        if (!file.exists()) return
        try { Os.chmod(file.absolutePath, 0x1ED) } catch (_: Exception) { file.setExecutable(true, false) }
    }

    /** Create [f] as an empty file if it doesn't exist (e.g. the stat/vmstat bind sources). */
    private fun emptyFile(f: File) {
        if (!f.exists()) {
            f.parentFile?.mkdirs()
            runCatching { f.createNewFile() }
        }
    }

    override fun startSession(cols: Int, rows: Int) {
        // A leftover session that already finished (running=false) must be cleared, otherwise the
        // early-return below would refuse to start a fresh shell — leaving the view stuck on a dead
        // session ("El shell terminó").
        val current = session
        if (current != null) {
            if (_running.value) return
            runCatching { current.finishIfRunning() }
            session = null
        }
        if (_setup.value !is TerminalSetupState.Ready) {
            Log.w(TAG, "startSession called before Ready; ignoring")
            return
        }
        val proot = prootExec()
        val loader = prootLoader()
        val loader32 = prootLoader32()
        val initHost = File(localBinDir(), "init-host.sh")
        val alpineRoot = alpineDir()

        // /system/bin/sh is the *first* process — Android bionic, not Termux-built, resolves
        // natively under scoped-linker namespaces. proot is launched by init-host.sh, not here.
        // ReTerminal MkSession.kt:151 calls TerminalSession with shell="/system/bin/sh" args=
        // arrayOf("-c", initHost.absolutePath) — the exact pattern we mirror below.
        val shell = "/system/bin/sh"
        val args = arrayOf("-c", initHost.absolutePath)

        // Env block — matches ReTerminal MkSession.kt:80-110. PROOT and PROOT_LOADER are the
        // absolute paths init-host.sh expects; LINKER lets it pick linker64 vs linker for the
        // alpine rootfs's init shell if it ever needs to re-exec; LD_LIBRARY_PATH makes
        // libtalloc.so.2 (symlinked in $PREFIX/local/lib/) resolvable.
        val env = mutableListOf<String>().apply {
            add("ANDROID_ART_ROOT=${System.getenv("ANDROID_ART_ROOT") ?: ""}")
            add("ANDROID_DATA=${System.getenv("ANDROID_DATA") ?: ""}")
            add("ANDROID_I18N_ROOT=${System.getenv("ANDROID_I18N_ROOT") ?: ""}")
            add("ANDROID_ROOT=${System.getenv("ANDROID_ROOT") ?: ""}")
            add("ANDROID_RUNTIME_ROOT=${System.getenv("ANDROID_RUNTIME_ROOT") ?: ""}")
            add("ANDROID_TZDATA_ROOT=${System.getenv("ANDROID_TZDATA_ROOT") ?: ""}")
            add("BOOTCLASSPATH=${System.getenv("BOOTCLASSPATH") ?: ""}")
            add("DEX2OATBOOTCLASSPATH=${System.getenv("DEX2OATBOOTCLASSPATH") ?: ""}")
            add("EXTERNAL_STORAGE=${System.getenv("EXTERNAL_STORAGE") ?: ""}")
            // PREFIX is the *parent* of filesDir, matching ReTerminal MkSession.kt:99 — init-host.sh
            // uses $PREFIX/local/alpine as the chroot root and $PREFIX/local/bin/init as the
            // post-proot entry point.
            add("PREFIX=${prefixDir().absolutePath}")
            add("BIN=${localBinDir().absolutePath}")
            add("NATIVE_LIB_DIR=${nativeLibDir!!}")
            add("PROOT=${proot.absolutePath}")
            add("PROOT_LOADER=${loader.absolutePath}")
            loader32?.let { add("PROOT_LOADER_32=${it.absolutePath}") }
            // /system/bin/linker64 on 64-bit ABIs, /system/bin/linker on 32-bit. init-host.sh does
            // not consume this directly, but exposing it lets Alpine's initrc probe the right
            // dynamic linker if the user runs `ldd` inside the shell.
            add("LINKER=${if (File("/system/bin/linker64").exists()) "/system/bin/linker64" else "/system/bin/linker"}")
            add("PATH=${System.getenv("PATH")}:/sbin:${localBinDir().absolutePath}")
            add("HOME=${alpineRoot.absolutePath}/root")
            add("PUBLIC_HOME=${appContext?.getExternalFilesDir(null)?.absolutePath ?: ""}")
            add("COLORTERM=truecolor")
            add("TERM=xterm-256color")
            add("LANG=C.UTF-8")
            add("LD_LIBRARY_PATH=${localLibDir().absolutePath}")
            add("TMPDIR=${tmpDir().absolutePath}")
            add("PROOT_TMP_DIR=${tmpDir().absolutePath}")
            add("PKG=${appContext!!.packageName}")
            add("PKG_PATH=${appContext!!.applicationInfo.sourceDir}")
            // init-host.sh honors FDROID=true to extract the native libs from $PREFIX instead of
            // $NATIVE_LIB_DIR (legacy path from Termux's F-Droid-only build). We're not on F-Droid,
            // but exposing the var means a future Play-Store/F-Droid split can flip it without
            // touching init-host.sh.
            add("FDROID=false")
        }

        val s = TerminalSession(shell, alpineRoot.absolutePath, args, env.toTypedArray(), rows, this)
        session = s
        s.updateSize(cols, rows)
        _running.value = true
        Log.i(TAG, "ReTerminal session started: shell=$shell args=$args rootfs=${alpineRoot.absolutePath}")
    }

    override fun stopSession() {
        session?.finishIfRunning()
        session = null
        _running.value = false
    }

    override fun writeCommand(line: String) {
        session?.write(line + "\n")
    }

    /** Write a byte-for-byte sequence (escape sequences, control chars) — no trailing newline. */
    fun writeRaw(text: String) {
        session?.write(text)
    }

    // ── Non-interactive command execution inside the Alpine rootfs ────────
    // Mirrors the interactive orchestration: the *first* process is /system/bin/sh running the
    // run-host.sh script (assembles the proot argv with the same bind mounts as init-host.sh), then
    // `$PROOT $ARGS /bin/ash -c "<command>"`. Works for package installs (apk), one-shots, and
    // anything the interactive shell can do. Returns the exit code.
    fun runInPrefix(command: String, onOutput: ((String) -> Unit)? = null, cwd: File? = null): Int {
        val runHost = File(localBinDir(), "run-host.sh")
        if (!runHost.exists()) { Log.e(TAG, "run-host.sh missing — ensureReady not run"); return -1 }
        val proot = prootExec()
        val loader = prootLoader()
        val loader32 = prootLoader32()
        val alpineRoot = alpineDir()

        val env = mutableListOf<String>().apply {
            add("PREFIX=${prefixDir().absolutePath}")
            add("BIN=${localBinDir().absolutePath}")
            add("NATIVE_LIB_DIR=${nativeLibDir!!}")
            add("PROOT=${proot.absolutePath}")
            add("PROOT_LOADER=${loader.absolutePath}")
            loader32?.let { add("PROOT_LOADER_32=${it.absolutePath}") }
            add("TMPDIR=${tmpDir().absolutePath}")
            add("PROOT_TMP_DIR=${tmpDir().absolutePath}")
            add("HOME=${alpineRoot.absolutePath}/root")
            add("TERM=xterm-256color")
            add("LANG=C.UTF-8")
            add("PATH=${System.getenv("PATH")}:/bin:/sbin:${localBinDir().absolutePath}")
        }
        val pb = ProcessBuilder("/system/bin/sh", runHost.absolutePath, command)
        pb.environment().clear()
        pb.environment().putAll(env.map { it.split("=", limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } } })
        pb.directory(cwd ?: alpineRootRootDir())
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
        } catch (e: Exception) {
            Log.e(TAG, "runInPrefix failed", e)
            onOutput?.invoke("error: ${e.message}")
            -1
        }
    }

    private fun alpineRootRootDir() = File(alpineDir(), "root").apply { mkdirs() }

    // ── Runtime tooling (Node.js / npm / Python) via Alpine's apk ─────────
    // The Alpine rootfs extractions place packages under usr/ (minirootfs layout), so the checks
    // look under bin/ and usr/bin/. Installs run `apk update` first to refresh the index.
    fun isNodeInstalled(): Boolean =
        File(alpineDir(), "usr/bin/node").exists() || File(alpineDir(), "bin/node").exists()

    fun isNpmInstalled(): Boolean =
        File(alpineDir(), "usr/bin/npm").exists() || File(alpineDir(), "bin/npm").exists()

    fun isPythonInstalled(): Boolean =
        File(alpineDir(), "usr/bin/python3").exists() || File(alpineDir(), "usr/bin/python").exists() ||
            File(alpineDir(), "bin/python3").exists() || File(alpineDir(), "bin/python").exists()

    fun installNode(onProgress: (String) -> Unit = {}): Boolean {
        onProgress("Instalando Node.js + npm (apk)…")
        val rc = runInPrefix("apk add --no-cache nodejs npm", onOutput = onProgress)
        if (rc != 0) { onProgress("apk add nodejs/npm salió $rc"); return false }
        return isNodeInstalled() && isNpmInstalled()
    }

    fun installPython(onProgress: (String) -> Unit = {}): Boolean {
        onProgress("Instalando Python 3 + pip (apk)…")
        val rc = runInPrefix("apk add --no-cache python3 py3-pip", onOutput = onProgress)
        if (rc != 0) { onProgress("apk add python3 salió $rc"); return false }
        return isPythonInstalled()
    }

    // TerminalSessionClient — minimal; the vendored Termux session emits callbacks we don't need
    // to act on for the panel UI. Errors get logged so stalls in "Waiting for shell.." are visible
    // in `adb logcat -s TerminalEngine:*` instead of being silently swallowed by the view.
    override fun onTextChanged(c: TerminalSession) {}
    override fun onTitleChanged(c: TerminalSession) {}
    override fun onSessionFinished(f: TerminalSession) { _running.value = false; session = null }
    override fun onCopyTextToClipboard(s: TerminalSession, text: String) {
        val ctx = appContext ?: return
        runCatching {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(null, text))
        }.onFailure { Log.e(TAG, "clipboard copy failed", it) }
    }
    override fun onPasteTextFromClipboard(s: TerminalSession?) {
        val ctx = appContext ?: return
        runCatching {
            val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clip.hasPrimaryClip()) {
                val text = clip.primaryClip?.getItemAt(0)?.coerceToText(ctx).toString()
                if (text.isNotEmpty()) session?.write(text)
            }
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

    /** Approximate sizes for the user-facing "Extracting… ~N MB" status line. */
    private object AssetSizes {
        fun rootfs(abi: String): Int = when (abi) {
            "arm64-v8a" -> 4
            "armeabi-v7a" -> 3
            else -> 3
        }
    }
}
