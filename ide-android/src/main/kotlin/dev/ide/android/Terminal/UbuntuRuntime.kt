package dev.ide.android.Terminal

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
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Ubuntu guest engine — a full Ubuntu jammy (22.04, arm64) userland running inside the app's proot.
 *
 * This is the "Fase 3 / caso de uso #6" requested upstream (CodeAssist's terminal feature request):
 * instead of the Alpine minirootfs of [TerminalEngine], it provisions a REAL Ubuntu rootfs
 * (`ubuntu-base-22.04-base-arm64.tar.gz`, ~27 MB download, ~75 MB extracted) under
 * `$PREFIX/local/ubuntu/` and runs it through the same proot chain (libproot.so + libloader.so).
 * Ubuntu's binaries are glibc-linked and resolve their NEEDED entries (ld-linux-aarch64.so.1,
 * libc.so.6, …) from INSIDE the rootfs, so proot's ptrace-execve works — exactly the
 * "UserLAnd / proot-distro" pattern that runs Ubuntu fine on this very device in another app.
 *
 * Why proot and not the linker64 route (TermuxRuntime): any glibc (Ubuntu) executable must be
 * loaded by ld-linux-aarch64.so.1, which Android does not ship. proot's loader provides exactly
 * that glue for guest ELFs; TermuxRuntime's /system/bin/linker64 trick covers only bionic
 * (Termux-built) binaries. This engine and TermuxRuntime coexist: the Activity picks one per run.
 *
 * Layout mirrors TerminalEngine's (`$PREFIX` = parent of filesDir per ReTerminal MkSession.kt:99):
 *   local/                (= $PREFIX/local, what init-ubuntu-host.sh and run-ubuntu-host.sh address)
 *     bin/                init-ubuntu-host.sh, init-ubuntu, run-ubuntu-host.sh (from assets, chmod +x)
 *     ubuntu/             extracted Ubuntu rootfs (bin/, usr/, etc/, …)
 *       root/             user home inside the chroot
 *       tmp/              /tmp bind-mapped for proot
 *     stat, vmstat        bind sources for /proc/{stat,vmstat}
 *
 * The rootfs is UNLIKE Alpine an asset: it is downloaded on first `ensureReady` from
 * cdimage.ubuntu.com (the canonical Ubuntu image host) and cached behind a marker. 22.04 ships a
 * ready-to-use `etc/apt/sources.list` pointing at ports.ubuntu.com (arm64) with main/universe/
 * multiverse/security, so `apt-get update && apt-get install …` works immediately inside.
 */
object UbuntuRuntime : TerminalSessionClient, TerminalRuntime {

    private const val TAG = "UbuntuRuntime"
    private const val UBUNTU_MARKER = ".cs-ubuntu-v1"
    private const val UBUNTU_BASE_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-arm64.tar.gz"
    private const val DISPLAY_NAME = "Ubuntu 22.04 (jammy)"

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

    // $PREFIX = the directory ABOVE files/ (ReTerminal convention, see TerminalEngine). All the
    // host scripts and the rootfs live under $PREFIX/local/...
    private fun prefixDir() = filesDir!!.parentFile!!
    private fun localDir() = File(prefixDir(), "local")
    private fun localBinDir() = File(localDir(), "bin")
    private fun localLibDir() = File(localDir(), "lib")
    private fun ubuntuDir() = File(localDir(), "ubuntu")
    private fun ubuntuTmpDir() = File(ubuntuDir(), "tmp").apply { mkdirs() }
    private fun ubuntuRootDir() = File(ubuntuDir(), "root").apply { mkdirs() }
    private fun tmpDir() = File(filesDir!!, "tmp").apply { mkdirs() }

    private fun prootExec() = File(nativeLibDir!!, "libproot.so")
    private fun prootLoader() = File(nativeLibDir!!, "libloader.so")
    private fun prootLoader32() = File(nativeLibDir!!, "libloader32.so").takeIf { it.exists() }
    private fun tallocLib() = File(nativeLibDir!!, "libtalloc.so").takeIf { it.exists() }

    override suspend fun ensureReady(onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        if (_setup.value is TerminalSetupState.Ready) return@withContext
        if (_setup.value is TerminalSetupState.Downloading || _setup.value is TerminalSetupState.Extracting) return@withContext
        try {
            val ctx = appContext ?: throw IllegalStateException("UbuntuRuntime.init(context) not called")
            val ubuntu = ubuntuDir()

            // 1. Host scripts (refreshed every run like TerminalEngine's — stale copies from an older
            //    build must not win over the shipped proot-argv templates).
            localBinDir().mkdirs()
            installAssetOnce(ctx, "init-ubuntu-host.sh", File(localBinDir(), "init-ubuntu-host.sh"), refresh = true)
            installAssetOnce(ctx, "init-ubuntu.sh", File(localBinDir(), "init-ubuntu"), refresh = true)
            installAssetOnce(ctx, "run-ubuntu-host.sh", File(localBinDir(), "run-ubuntu-host.sh"), refresh = true)

            // 2. Rootfs: download + extract the first time, cached behind a marker. bash presence is
            //    the liveness check (bin/ is a symlink to usr/bin/ in ubuntu-base, so check the file).
            val bash = File(ubuntu, "usr/bin/bash")
            if (File(ubuntu, UBUNTU_MARKER).exists() && bash.exists()) {
                onProgress("Reutilizando $DISPLAY_NAME")
            } else {
                _setup.value = TerminalSetupState.Downloading("Descargando $DISPLAY_NAME (~27 MB)…")
                onProgress("Descargando ubuntu-base…")
                val tarball = downloadUbuntuBase(onProgress)
                _setup.value = TerminalSetupState.Extracting
                onProgress("Extrayendo rootfs Ubuntu (~75 MB)…")
                ubuntu.deleteRecursively()
                ubuntu.mkdirs()
                extractGzipTarFile(tarball, ubuntu)
                File(ubuntu, UBUNTU_MARKER).writeText("ok: $DISPLAY_NAME\n")
                slotDpkgStatus(ubuntu)
            }

            // 3. Bind sources proot mounts as /proc/{stat,vmstat}; libraries the loader needs.
            emptyFile(File(localDir(), "stat"))
            emptyFile(File(localDir(), "vmstat"))
            localLibDir().mkdirs()
            installTallocLink()

            // 4. Always make sure the chroot is usable for a session.
            ubuntuTmpDir()
            ubuntuRootDir()
            ensureExecutable(prootExec())
            ensureExecutable(prootLoader())
            prootLoader32()?.let { ensureExecutable(it) }
            if (!File(ubuntu, "etc/resolv.conf").exists() || File(ubuntu, "etc/resolv.conf").length() == 0L) {
                File(ubuntu, "etc/resolv.conf").writeText("nameserver 8.8.8.8\n")
            }
            tmpDir()

            _setup.value = TerminalSetupState.Ready
            Log.i(TAG, "$DISPLAY_NAME ready at ${ubuntu.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "ensureReady failed", e)
            _setup.value = TerminalSetupState.Failed(e.message ?: "setup failed")
        }
    }

    /** Download ubuntu-base to a temp file under filesDir/tmp, streaming with a progress callback. */
    private fun downloadUbuntuBase(onProgress: (String) -> Unit): File {
        val out = File(tmpDir(), "ubuntu-base.tar.gz")
        if (out.exists() && out.length() > 100_000_000L) return out // sanity: caching not really needed (marker gates)
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, "ubuntu-base.tar.gz.download")
        val conn = URL(UBUNTU_BASE_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true
        tmp.delete()
        conn.inputStream.use { input ->
            BufferedInputStream(input).use { bis ->
                FileOutputStream(tmp).use { fos ->
                    val buf = ByteArray(262_144)
                    var n: Int
                    var total = 0L
                    while (true) {
                        n = bis.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                        total += n
                        if (total % (4 * 1024 * 1024) == 0L) onProgress("Descargando… ${total / (1024 * 1024)} MB")
                    }
                }
            }
        }
        if (tmp.length() < 1_000_000L) throw IllegalStateException("Descarga ubuntu-base demasiado pequeña (${tmp.length()} bytes)")
        val magic = FileInputStream(tmp).use { `is` ->
            val h = ByteArray(2); val got = `is`.read(h); got == 2 && h[0] == 0x1F.toByte() && h[1] == 0x8B.toByte()
        }
        if (!magic) throw IllegalStateException("ubuntu-base descargado no es un gzip (magic inválido)")
        tmp.renameTo(out)
        return out
    }

    /**
     * ubuntu-base ships etc/apt/sources.list WITHOUT the official keyed deb822 list that normal
     * Ubuntu installs get: the base image only carries `deb http://ports.ubuntu.com/ubuntu-ports/ …`
     * lines and no signed Release file is ensured. Keep it (arm64 ports is correct), but drop any
     * auto-added `…/ubuntu.sources` leftovers that could double-list repositories.
     */
    private fun slotDpkgStatus(ubuntu: File) {
        // No-op placeholder documenting that the base image's sources.list is already correct for
        // arm64; keep the method so first-run post-extract tweaks live in one obvious place.
        val keyrings = File(ubuntu, "etc/apt/keyrings")
        runCatching { keyrings.mkdirs() }
    }

    private fun installTallocLink() {
        val talloc = tallocLib() ?: return
        localLibDir().mkdirs()
        val link = File(localLibDir(), "libtalloc.so.2")
        if (!link.exists()) {
            runCatching { Os.symlink(talloc.absolutePath, link.absolutePath) }
                .onFailure { link.writeBytes(talloc.readBytes()) }
        }
    }

    /** Copy an APK asset into $PREFIX/local/bin. Refresh=true overwrites (host templates must track
     *  the shipped version); the rootfs user data is never touched here. */
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
     * Stream-extract a gzipped tar into [dest]. Unlike TerminalEngine's extractor (which handles plain
     * USTAR Alpine minirootfs), ubuntu-base uses PAX extended headers (typeflags 'x'/'g') for long
     * names/sizes and ships symlinks + hardlinks, so this covers: PAX 'x'/'g' (applied to the next
     * entry), GNU 'L'/'K' long-name workalikes, regular/dir/symlink/hardlink, and mode bits. The
     * format is POSIX ustar; gzip container via GZIPInputStream.
     */
    private fun extractGzipTarFile(tarball: File, dest: File) {
        val buf = ByteArray(128 * 1024)
        val header = ByteArray(512)
        val longName = StringBuilder()
        var paxPath: String? = null
        var paxLink: String? = null
        var paxSize: Long? = null
        FileInputStream(tarball).use { raw ->
            GZIPInputStream(BufferedInputStream(raw)).use { gz ->
                while (true) {
                    var got = 0
                    while (got < 512) {
                        val n = gz.read(header, got, 512 - got)
                        if (n < 0) return@use
                        got += n
                    }
                    if (header.all { it == 0.toByte() }) return@use

                    val name = readCString(header, 0, 100).takeIf { it.isNotEmpty() } ?: continue
                    val mode = readOctal(header, 100, 8)
                    var size = readOctal(header, 124, 12)
                    val typeFlag = header[156].toInt().toChar()
                    val linkName = readCString(header, 157, 100)

                    if (typeFlag == 'x' || typeFlag == 'g') {
                        // PAX extended header: parse "length key=value\n" records for path/linkpath/size.
                        val data = ByteArray(size.toInt())
                        readFully(gz, data)
                        val text = String(data, Charsets.UTF_8)
                        var idx = 0
                        while (idx < text.length) {
                            val sp = text.indexOf(' ', idx)
                            val nl = text.indexOf('\n', sp)
                            if (sp < 0 || nl < 0) break
                            val len = text.substring(idx, sp).toIntOrNull() ?: break
                            val record = text.substring(sp + 1, minOf(nl, text.length))
                            val eq = record.indexOf('=')
                            if (eq > 0) {
                                val key = record.substring(0, eq)
                                val value = record.substring(eq + 1).trimEnd()
                                when (key) {
                                    "path" -> paxPath = value
                                    "linkpath" -> paxLink = value
                                    "size" -> paxSize = value.toLongOrNull()
                                }
                            }
                            idx = nl + 1
                        }
                        skipBytes(gz, ((size + 511) / 512) * 512 - size)
                        continue
                    }
                    if (typeFlag == 'L' || typeFlag == 'K') {
                        // GNU long-name (L) / long-link (K) — data IS the full name.
                        val data = ByteArray(size.toInt())
                        readFully(gz, data)
                        val long = String(data, Charsets.UTF_8).trimEnd('\u0000', '\n')
                        if (typeFlag == 'L') longName.setLength(0); longName.append(long)
                        skipBytes(gz, ((size + 511) / 512) * 512 - size)
                        continue
                    }

                    var entryName = if (longName.isNotEmpty()) longName.toString() else name
                    paxPath?.let { entryName = it }
                    val entryLink = paxLink ?: linkName
                    if (paxSize != null) size = paxSize!!.toLong()  // PAX size overrides the header field

                    if (typeFlag == '5' || entryName.endsWith("/")) {
                        File(dest, entryName).mkdirs()
                        skipBytes(gz, ((size + 511) / 512) * 512 - size)
                        paxPath = null; paxLink = null; paxSize = null; longName.setLength(0)
                        continue
                    }
                    if (typeFlag == '2') {
                        runCatching {
                            val target = File(dest, entryLink)
                            val link = File(dest, entryName)
                            link.parentFile?.mkdirs()
                            if (link.exists()) link.delete()
                            Os.symlink(entryLink, link.absolutePath)
                        }.onFailure { Log.w(TAG, "symlink $entryName -> $entryLink falló: ${it.message}") }
                        skipBytes(gz, ((size + 511) / 512) * 512 - size)
                        paxPath = null; paxLink = null; paxSize = null; longName.setLength(0)
                        continue
                    }
                    if (typeFlag == '1') {
                        // Hardlink: copy the referent's bytes (treat as file copy to keep it self-contained
                        // on F2FS/app-data where hardlinks across dirs are not always allowed).
                        runCatching {
                            val src = File(dest, entryLink)
                            val link = File(dest, entryName)
                            link.parentFile?.mkdirs()
                            if (src.exists() && src.isFile) src.copyTo(link, overwrite = true)
                        }.onFailure { Log.w(TAG, "hardlink $entryName -> $entryLink falló: ${it.message}") }
                        skipBytes(gz, ((size + 511) / 512) * 512 - size)
                        paxPath = null; paxLink = null; paxSize = null; longName.setLength(0)
                        continue
                    }

                    // Regular file (typeflag '0', '\0', or bare).
                    val out = File(dest, entryName)
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
                    val execBits = (mode.toInt() and 511)
                    if (execBits != 0) {
                        runCatching { Os.chmod(out.absolutePath, execBits) }
                            .onFailure { out.setExecutable(true, false) }
                    }
                    skipBytes(gz, ((size + 511) / 512) * 512 - size)
                    paxPath = null; paxLink = null; paxSize = null; longName.setLength(0)
                }
            }
        }
    }

    private fun readFully(gz: java.io.InputStream, b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val n = gz.read(b, off, b.size - off)
            if (n < 0) break
            off += n
        }
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

    private fun readCString(buf: ByteArray, offset: Int, maxLen: Int): String {
        val end = (offset until offset + maxLen).firstOrNull { buf[it] == 0.toByte() } ?: (offset + maxLen)
        return String(buf, offset, end - offset, Charsets.US_ASCII).trim()
    }

    private fun readOctal(buf: ByteArray, offset: Int, len: Int): Long {
        var result = 0L
        for (i in 0 until len) {
            val c = buf[offset + i].toInt().toChar()
            if (c == '\u0000' || c == ' ') continue
            if (c !in '0'..'7') break
            result = result * 8 + (c - '0')
        }
        return result
    }

    private fun emptyFile(f: File) {
        if (!f.exists()) {
            f.parentFile?.mkdirs()
            runCatching { f.createNewFile() }
        }
    }

    private fun ensureExecutable(file: File) {
        if (!file.exists()) return
        try { Os.chmod(file.absolutePath, 0x1ED) } catch (_: Exception) { file.setExecutable(true, false) }
    }

    // ── Interactive session ──────────────────────────────────────────────
    // Reuses the ReTerminal orchestration: the FIRST process is /system/bin/sh (bionic, resolves
    // natively), init-ubuntu-host.sh assembles the proot argv with the Android 11+ bind mounts and
    // launches proot against $PREFIX/local/ubuntu; proot then runs init-ubuntu (Ubuntu's bash -l).
    override fun startSession(cols: Int = 80, rows: Int = 24) {
        val current = session
        if (current != null) {
            if (_running.value) return
            runCatching { current.finishIfRunning() }
            session = null
        }
        if (_setup.value !is TerminalSetupState.Ready) {
            Log.w(TAG, "startSession before Ready; ignoring")
            return
        }
        val initHost = File(localBinDir(), "init-ubuntu-host.sh")
        val shell = "/system/bin/sh"
        val args = arrayOf("-c", initHost.absolutePath)
        val env = buildEnv()
        val s = TerminalSession(shell, ubuntuDir().absolutePath, args, env.toTypedArray(), rows, this)
        session = s
        s.updateSize(cols, rows)
        _running.value = true
        Log.i(TAG, "Ubuntu session started: shell=$shell rootfs=${ubuntuDir().absolutePath}")
    }

    private fun buildEnv(): MutableList<String> = mutableListOf<String>().apply {
        add("ANDROID_ART_ROOT=${System.getenv("ANDROID_ART_ROOT") ?: ""}")
        add("ANDROID_DATA=${System.getenv("ANDROID_DATA") ?: ""}")
        add("ANDROID_I18N_ROOT=${System.getenv("ANDROID_I18N_ROOT") ?: ""}")
        add("ANDROID_ROOT=${System.getenv("ANDROID_ROOT") ?: ""}")
        add("ANDROID_RUNTIME_ROOT=${System.getenv("ANDROID_RUNTIME_ROOT") ?: ""}")
        add("ANDROID_TZDATA_ROOT=${System.getenv("ANDROID_TZDATA_ROOT") ?: ""}")
        add("BOOTCLASSPATH=${System.getenv("BOOTCLASSPATH") ?: ""}")
        add("DEX2OATBOOTCLASSPATH=${System.getenv("DEX2OATBOOTCLASSPATH") ?: ""}")
        add("EXTERNAL_STORAGE=${System.getenv("EXTERNAL_STORAGE") ?: ""}")
        add("PREFIX=${prefixDir().absolutePath}")
        add("BIN=${localBinDir().absolutePath}")
        add("NATIVE_LIB_DIR=${nativeLibDir!!}")
        add("PROOT=${prootExec().absolutePath}")
        add("PROOT_LOADER=${prootLoader().absolutePath}")
        prootLoader32()?.let { add("PROOT_LOADER_32=${it.absolutePath}") }
        add("LINKER=${if (File("/system/bin/linker64").exists()) "/system/bin/linker64" else "/system/bin/linker"}")
        add("PATH=${System.getenv("PATH")}:/sbin:${localBinDir().absolutePath}")
        add("HOME=${ubuntuDir().absolutePath}/root")
        add("PUBLIC_HOME=${appContext?.getExternalFilesDir(null)?.absolutePath ?: ""}")
        add("COLORTERM=truecolor")
        add("TERM=xterm-256color")
        add("LANG=C.UTF-8")
        add("LD_LIBRARY_PATH=${localLibDir().absolutePath}")
        add("TMPDIR=${tmpDir().absolutePath}")
        add("PROOT_TMP_DIR=${tmpDir().absolutePath}")
        add("PKG=${appContext!!.packageName}")
        add("PKG_PATH=${appContext!!.applicationInfo.sourceDir}")
        add("FDROID=false")
    }

    // ── Non-interactive command execution inside the Ubuntu rootfs ───────
    fun runInPrefix(command: String, onOutput: ((String) -> Unit)? = null, cwd: File? = null): Int {
        val runHost = File(localBinDir(), "run-ubuntu-host.sh")
        if (!runHost.exists()) { Log.e(TAG, "run-ubuntu-host.sh missing — ensureReady not run"); return -1 }
        val proot = prootExec()
        val loader = prootLoader()
        val loader32 = prootLoader32()
        val env = mutableListOf<String>().apply {
            add("PREFIX=${prefixDir().absolutePath}")
            add("BIN=${localBinDir().absolutePath}")
            add("NATIVE_LIB_DIR=${nativeLibDir!!}")
            add("PROOT=${proot.absolutePath}")
            add("PROOT_LOADER=${loader.absolutePath}")
            loader32?.let { add("PROOT_LOADER_32=${it.absolutePath}") }
            add("TMPDIR=${tmpDir().absolutePath}")
            add("PROOT_TMP_DIR=${tmpDir().absolutePath}")
            add("HOME=${ubuntuDir().absolutePath}/root")
            add("TERM=xterm-256color")
            add("LANG=C.UTF-8")
            add("PATH=${System.getenv("PATH")}:/usr/sbin:/usr/bin:/sbin:/bin:${localBinDir().absolutePath}")
        }
        val pb = ProcessBuilder("/system/bin/sh", runHost.absolutePath, command)
        pb.environment().clear()
        pb.environment().putAll(env.map { it.split("=", limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } } })
        pb.directory(cwd ?: ubuntuRootDir())
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

    // ── Tooling probes (Node / Python inside Ubuntu) ─────────────────────
    fun isNodeInstalled(): Boolean =
        File(ubuntuDir(), "usr/bin/node").exists() || File(ubuntuDir(), "bin/node").exists()
    fun isNpmInstalled(): Boolean =
        File(ubuntuDir(), "usr/bin/npm").exists() || File(ubuntuDir(), "bin/npm").exists()
    fun isPythonInstalled(): Boolean =
        File(ubuntuDir(), "usr/bin/python3").exists() || File(ubuntuDir(), "bin/python3").exists() ||
            File(ubuntuDir(), "usr/bin/python").exists() || File(ubuntuDir(), "bin/python").exists()

    fun installNode(onProgress: (String) -> Unit = {}): Boolean {
        onProgress("Instalando Node.js + npm (apt)…")
        val rc = runInPrefix("apt-get update >/dev/null 2>&1; DEBIAN_FRONTEND=noninteractive apt-get install -y nodejs npm 2>&1 && echo APT_OK", onOutput = onProgress)
        if (rc != 0) { onProgress("apt install nodejs/npm salió $rc"); return false }
        return isNodeInstalled() && isNpmInstalled()
    }

    fun installPython(onProgress: (String) -> Unit = {}): Boolean {
        onProgress("Instalando Python 3 + pip (apt)…")
        val rc = runInPrefix("apt-get update >/dev/null 2>&1; DEBIAN_FRONTEND=noninteractive apt-get install -y python3 python3-pip 2>&1 && echo APT_OK", onOutput = onProgress)
        if (rc != 0) { onProgress("apt install python3 salió $rc"); return false }
        return isPythonInstalled()
    }

    override fun stopSession() {
        session?.finishIfRunning()
        session = null
        _running.value = false
    }

    override fun writeCommand(line: String) {
        session?.write(line + "\n")
    }

    // ── TerminalSessionClient (same minimal set as TerminalEngine) ───────
    @Volatile
    var onScreenChanged: ((TerminalSession) -> Unit)? = null
    override fun onTextChanged(c: TerminalSession) {
        onScreenChanged?.invoke(c)
    }
    override fun onTitleChanged(c: TerminalSession) {}
    override fun onSessionFinished(f: TerminalSession) {
        _running.value = false
        session = null
    }
    override fun onCopyTextToClipboard(s: TerminalSession, text: String) {
        val ctx = appContext ?: return
        runCatching {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText(null, text))
        }.onFailure { Log.e(TAG, "clipboard copy failed", it) }
    }
    override fun onPasteTextFromClipboard(s: TerminalSession?) {
        val ctx = appContext ?: return
        runCatching {
            val clip = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
            if (!clip.isNullOrEmpty()) session?.write(clip)
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