package dev.ide.android

import android.app.ActivityManager
import android.content.Context
import dev.ide.platform.ForkedToolVm
import dev.ide.platform.log.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Shared on-device machinery for running R8/D8 in a FORKED command-line VM (the release/minify OOM fix,
 * docs/build-process-isolation.md) — used by [ForkedR8Shrinker] (the build path), [dev.ide.android.ForkedD8Dexer]
 * (the dex merge/one-pass) and the "Detect device limit" settings action ([detectCeiling]).
 *
 * A forked `dalvikvm`/`art` VM is NOT a zygote app process, so its `-Xmx` can exceed the app's `largeHeap`
 * cap (576MB on the measured device, ceiling ~1.5GB). R8's classes ship as a dedicated dex asset
 * (`r8.dex.zip`, the `bundleR8DexAsset` build task) because the app's own copy is in secondary dexes a bare
 * `-cp base.apk` won't load.
 */
object R8ForkSupport {
    private val log = Log.logger("ide.mem")
    const val R8_DEX_ASSET = "r8.dex.zip"

    /** VM binaries that take `-cp <dexes> <class>` and build a multidex-aware classloader, inheriting
     *  BOOTCLASSPATH from this app process. First existing wins. `app_process` is unusable here (it resolves
     *  the start class via the system loader, which misses an app class in a large multidex apk).
     *  Their basenames are what a tombstone reports as the faulting thread of a fork, so they are mirrored in
     *  [ForkedToolVm.LAUNCHER_THREAD_NAMES] — keep the two lists in step. */
    val LAUNCHERS = listOf(
        "/apex/com.android.art/bin/dalvikvm64",
        "/apex/com.android.art/bin/dalvikvm32",
        "/apex/com.android.art/bin/dalvikvm",
        "/system/bin/dalvikvm",
    )

    fun launcher(): String? = LAUNCHERS.firstOrNull { File(it).exists() }

    /** `dex\n035\0` header magic every dalvik dex starts with. */
    private val DEX_MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0a) // "dex\n"

    /**
     * Header-complete check for one dex: magic + `file_size` (bytes 32..35) == the on-disk length +
     * `header_size` (36..39) == 0x70 + `endian_tag` (40..43) == 0x12345678. The [DEX_MAGIC]-only test below
     * passes a TRUNCATED dex (a killed/lossy write keeps the first 4 bytes) — and a truncated dex is the
     * classic "ForkedD8Dexer failed with `ClassNotFoundException: com.android.tools.r8.D8` while the R8
     * probe passed" scenario: ART finds the classes that happen to precede the cut and misses the rest. A
     * magic-only check let that straight onto the forked VM classpath.
     */
    private fun dexHeaderValid(f: File): Boolean = runCatching {
        f.inputStream().use { i ->
            val b = ByteArray(44)
            if (i.read(b) != 44) return@runCatching false
            if (!b.copyOfRange(0, 4).contentEquals(DEX_MAGIC)) return@runCatching false
            val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            val fileSize = buf.getInt(32).toLong() and 0xFFFFFFFFL
            val headerSize = buf.getInt(36)
            val endianTag = buf.getInt(40)
            headerSize == 0x70 && endianTag == 0x12345678 && fileSize == f.length()
        }
    }.getOrDefault(false)

    /** Whether [dexes] is a usable forked-VM classpath: non-empty, non-directory, non-zero-sized, and each
     *  carrying a consistent header. ART fails the WHOLE VM classpath for a corrupt entry, and the marker guard
     *  below can return a stale/truncated extraction whose marker still matches — so every path validates. */
    private fun valid(dexes: List<File>): Boolean = dexes.isNotEmpty() && dexes.all { f ->
        f.isFile && f.length() > 0 && dexHeaderValid(f)
    }

    /** Clear any (possibly read-only) dexes/chunks in [dir] so a fresh extraction can't hit a read-only file. */
    private fun clearDir(dir: File) {
        dir.listFiles()?.forEach { runCatching { it.setWritable(true); it.delete() } }
    }

    /**
     * Run [body] holding BOTH a process-local monitor and (when another process is extracting at the same
     * time) an exclusive file lock on the extraction dir. The IDE process and the separate `:build` daemon can
     * extract `r8.dex.zip` concurrently (the daemon for the build, the IDE e.g. for a "Detect device limit"
     * probe / preview dexing): two interleaved extractions unlink + rewrite the same files and leave magic-valid
     * but truncated dexes — exactly the `ClassNotFoundException: com.android.tools.r8.D8` reports. The lock
     * makes extraction atomic across processes; the re-validated header check ([valid]) catches any fresh loss.
     */
    private fun <T> withExtractLock(dir: File, body: () -> T): T {
        synchronized(R8ForkSupport) {
            Files.createDirectories(dir.toPath())
            val lockFile = File(dir, ".extract.lock")
            FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
                val lock: FileLock = ch.lock()
                try {
                    return body()
                } finally {
                    runCatching { lock.release() }
                }
            }
        }
    }

    /**
     * Extract `assets/r8.dex.zip` → `cacheDir/r8-dex/` and return its `classes*.dex`, made READ-ONLY.
     *
     * The read-only part is load-bearing: ART refuses to load a WRITABLE dex on a command-line VM's classpath
     * (W^X — `SecurityException: Writable dex file '…' is not allowed`, aborting the VM at system-classloader
     * creation). A freshly-extracted file is writable, so each is `setReadOnly()` after writing. Marker-guarded
     * by the app's `lastUpdateTime` so a new APK (possibly a new r8) re-extracts; the stale read-only files are
     * cleared first so the rewrite can't hit a read-only file. Defensive: a marker match is also re-validated
     * ([valid]) and, on failure, re-extracted once — a truncated on-disk dex (a killed write, a stale folder
     * from an older app) otherwise passes straight to a forked merge that can't locate r8's classes.
     */
    fun extractR8Dexes(context: Context): List<File>? {
        val ctx = context.applicationContext
        val dir = File(ctx.cacheDir, "r8-dex")
        val stamp = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime }.getOrDefault(0L).toString()
        val marker = File(dir, ".extracted")
        fun dexes() = dir.listFiles { f -> f.name.endsWith(".dex") }?.toList()?.sortedBy { it.name }
        val cached = dexes()?.takeIf { marker.exists() && marker.readText() == stamp }
        if (cached != null) {
            if (valid(cached)) return cached
            log.warn("r8-fork: ${cached.size} cached dex in $dir failed the header check — re-extracting $R8_DEX_ASSET")
            clearDir(dir)
        }
        dir.mkdirs()
        return withExtractLock(dir) {
            // Re-read inside the lock: the other process may have just completed a fresh extraction whose
            // dexes are already valid — re-extracting over them would needlessly race-free but rewrite the same.
            val revalidated = dexes()?.takeIf { marker.exists() && marker.readText() == stamp }
            if (revalidated != null && valid(revalidated)) return@withExtractLock revalidated
            // Clear any stale (read-only) dexes from a prior extract so the fresh write can't hit a read-only file.
            clearDir(dir)
            runCatching {
                ctx.assets.open(R8_DEX_ASSET).use { ins ->
                    ZipInputStream(ins.buffered()).use { zis ->
                        var e = zis.nextEntry
                        while (e != null) {
                            if (!e.isDirectory && e.name.endsWith(".dex")) {
                                val f = File(dir, File(e.name).name)
                                f.outputStream().use { out -> zis.copyTo(out) }
                                f.setReadOnly() // ART won't load a writable dex on a VM classpath (W^X)
                            }
                            e = zis.nextEntry
                        }
                    }
                }
                marker.writeText(stamp)
                val ds = dexes()?.takeIf { it.isNotEmpty() }
                if (ds == null || !valid(ds)) {
                    log.warn("r8-fork: extracted dexes failed validation — forked-R8 unavailable, in-process fallback")
                    null
                } else ds
            }.onFailure { log.warn("r8-fork: failed to extract $R8_DEX_ASSET: ${it.message}") }.getOrNull()
        }
    }

    /** The error-frame signatures that mean "the tool's classes didn't load", not "the tool rejected input". */
    private val LOAD_FAILURE = Regex("""(ClassNotFoundException|NoClassDefFoundError)""")

    /** Whether [mainClass] loads in a forked [launcher] VM at [xmxMb] with [dexes] as the classpath. A load
     *  failure leaves a `ClassNotFoundException`/`NoClassDefFoundError` stack in the merged output; a loaded
     *  tool that merely dislikes the probe flag still counts (any exit code, no load-failure frames) — the
     *  probe verifies the CLASS, not the flags. */
    fun probeMain(launcher: String, dexes: List<File>, xmxMb: Int, mainClass: String): Boolean = runCatching {
        val cp = dexes.joinToString(File.pathSeparator) { it.absolutePath }
        val proc = ProcessBuilder(launcher, "-Xmx${xmxMb}m", "-cp", cp, mainClass, "--version")
            .redirectErrorStream(true).start()
        if (!proc.waitFor(30, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return@runCatching false
        }
        val out = runCatching { proc.inputStream.bufferedReader().readText() }.getOrDefault("")
        !LOAD_FAILURE.containsMatchIn(out) && !out.contains("Could not find class")
    }.getOrDefault(false)

    /** True if a forked `launcher -Xmx<n>m -cp <r8 dexes> com.android.tools.r8.R8 --version` starts (heap
     *  granted + R8 loaded). */
    fun canFork(launcher: String, dexes: List<File>, xmxMb: Int): Boolean = runCatching {
        val cp = dexes.joinToString(File.pathSeparator) { it.absolutePath }
        val proc = ProcessBuilder(launcher, "-Xmx${xmxMb}m", "-cp", cp, "com.android.tools.r8.R8", "--version")
            .redirectErrorStream(true).start()
        if (!proc.waitFor(30, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return false
        }
        val out = runCatching { proc.inputStream.bufferedReader().readText() }.getOrDefault("")
        proc.exitValue() == 0 && !LOAD_FAILURE.containsMatchIn(out)
    }.getOrDefault(false)

    /**
     * The largest heap (MB) a forked VM grants while loading R8, or null if forking is unavailable (no
     * launcher / missing asset / no heap this device can back). Scans the ladder ascending and stops at the
     * first rejection — heap reservation is monotonic in `-Xmx`, so the last accepted value is the ceiling.
     * Forks several VMs (~0.5s each), so call off the main thread.
     *
     * The ladder is first trimmed to what the device's RAM can back ([affordableHeaps]), because a rejection
     * is not a polite `false`: a VM that cannot reserve its region space ABORTS, and the OS files that abort
     * under this package. Trimming stops a small phone at a rung it can actually reach instead of walking up
     * into a certain abort, and a device that grants the whole trimmed ladder never aborts at all.
     */
    fun detectCeiling(context: Context): Int? {
        val launcher = launcher() ?: return null
        val dexes = extractR8Dexes(context) ?: return null
        val ladder = affordableHeaps(context, CEILING_LADDER)
        if (ladder.isEmpty()) {
            log.info("r8-fork: ${totalMemMb(context)}MB of device RAM can't back even a ${CEILING_LADDER.first()}MB fork — forking unavailable")
            return null
        }
        var ceiling: Int? = null
        for (mb in ladder) {
            if (canFork(launcher, dexes, mb)) ceiling = mb else break
        }
        log.info("r8-fork: detected forked-VM ceiling = ${ceiling ?: "none"}MB (app cap ${Runtime.getRuntime().maxMemory() / (1024 * 1024)}MB)")
        return ceiling
    }

    private val CEILING_LADDER = listOf(768, 1024, 1536, 2048, 3072, 4096)

    /**
     * [candidates] (`-Xmx` values in MB) minus the ones this device's RAM visibly cannot back, order kept.
     * Every fork path filters through this so no path launches a VM whose only possible outcome is an ART
     * startup abort; see [ForkedToolVm.affordableHeaps] for why the bound is on 2× the heap.
     */
    fun affordableHeaps(context: Context, candidates: List<Int>): List<Int> =
        ForkedToolVm.affordableHeaps(candidates, totalMemMb(context))

    /** Device-wide physical RAM (MB) via [ActivityManager.MemoryInfo.totalMem]; 0 if unavailable. Unlike
     *  [availableMemMb] this doesn't move with load — it bounds what a heap RESERVATION can ever be. */
    fun totalMemMb(context: Context): Long = runCatching {
        val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.totalMem / (1024 * 1024)
    }.getOrDefault(0L)

    // --- Concurrent-fork budget + process-wide gate ---------------------------------------------------------

    /** Hard cap on concurrent forked VMs regardless of how much RAM the device has — each fork still spawns a
     *  process and competes for cores, and the win over the old serial flood is mostly batching. */
    const val MAX_CONCURRENT_FORKS = 3

    /** Fraction of *available* device RAM budgeted per concurrent fork. Generous (a fork's `-Xmx` is an upper
     *  bound on its RSS, rarely the steady state) but leaves headroom so the low-memory killer stays away. */
    private const val FORK_RAM_FRACTION = 0.5

    /** Device-wide available RAM (MB) via [ActivityManager.MemoryInfo.availMem]; 0 if unavailable. */
    fun availableMemMb(context: Context): Long = runCatching {
        val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.availMem / (1024 * 1024)
    }.getOrDefault(0L)

    /**
     * How many forked VMs of [xmxMb] this device can safely run at once. [override] (the user's "Max concurrent
     * dex forks" setting) wins when > 0; otherwise it's derived from available RAM ([FORK_RAM_FRACTION] of
     * availMem per fork), clamped to `[1, min(cores, MAX_CONCURRENT_FORKS)]`. Under memory pressure availMem
     * collapses this to 1 — one big fork, still far better than the old fork-per-library flood.
     */
    fun forkBudget(context: Context, xmxMb: Int, override: Int?): Int {
        if (override != null && override > 0) return override.coerceIn(1, 8)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val availMb = availableMemMb(context)
        val byRam = if (availMb > 0 && xmxMb > 0) ((availMb * FORK_RAM_FRACTION) / xmxMb).toInt() else 1
        return byRam.coerceIn(1, minOf(cores, MAX_CONCURRENT_FORKS))
    }

    @Volatile
    private var forkGate: Semaphore? = null

    /** Process-wide gate sized once on first use (fair, FIFO). Init-once because resizing a live semaphore is
     *  racy and the budget (device RAM / fork heap) doesn't change within a build — a setting change applies on
     *  the next build-process start. */
    @Synchronized
    private fun gate(permits: Int): Semaphore =
        forkGate ?: Semaphore(permits.coerceAtLeast(1), true).also {
            forkGate = it
            log.info("fork gate: capped at $permits concurrent forked VM(s)")
        }

    /**
     * Run [body] (a forked-VM launch) holding one permit on the process-wide concurrent-fork gate, so the
     * sibling dex-merge tasks — `mergeProjectDex`/`mergeLibDex`/`mergeExtDex` run in parallel and each forks —
     * can't collectively spawn more big-heap VMs than the device affords. Blocking acquire (callers are on an
     * IO dispatcher). [permits] sizes the gate on first use only.
     */
    fun <T> withForkPermit(permits: Int, body: () -> T): T {
        val g = gate(permits)
        g.acquire()
        return try { body() } finally { g.release() }
    }
}
