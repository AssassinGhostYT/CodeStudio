package dev.ide.android.support

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Supplies the AndroidX compile stubs (`AppCompatActivity`, `ActivityCompat`, `@NonNull`, …) WITHOUT a
 * network resolve.
 *
 * A project scaffolded offline declares `implementation("androidx.appcompat:appcompat:…")`, but the Maven
 * resolver can't reach Google's repository on a fresh offline install, so `androidx.appcompat.app
 * .AppCompatActivity` and friends fail to resolve in the editor (red imports, no completion). AndroidX is
 * not part of `android.jar` (that only carries the framework), so it has to be shipped separately.
 *
 * The AAR `classes.jar`s of the appcompat/core/annotation closure are bundled as classpath resources under
 * `/androidx-stubs/` (listed by `/androidx-stubs/manifest.txt`) and extracted to a real directory on demand.
 * They are appended to the EDITOR's analysis classpath only (see `ModuleCompilationContext.create`'s
 * `extraLibraries`), never the model or the dex path — so they resolve code without bloating a project's
 * APK or leaking into the Dependencies UI. Online, the project's own resolved AAR still wins (it is added
 * first) and is the one dexed.
 */
object BundledAndroidx {

    private const val RESOURCE_DIR = "/androidx-stubs"
    private const val MANIFEST = "$RESOURCE_DIR/manifest.txt"

    /** Extraction cache keyed by destination dir, so the 37 jars are read/extracted once per workspace. */
    private val extracted = ConcurrentHashMap<Path, List<Path>>()

    /** True when the stubs are bundled on the classpath (false only in a stripped-down test classpath). */
    fun isBundled(): Boolean = BundledAndroidx::class.java.getResource(MANIFEST) != null

    /** The bundled stub jar file names (from the manifest), in stable order. */
    fun names(): List<String> = runCatching {
        BundledAndroidx::class.java.getResourceAsStream(MANIFEST)?.bufferedReader()?.useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() && it.endsWith(".jar", ignoreCase = true) }.toList()
        } ?: emptyList()
    }.getOrDefault(emptyList())

    /**
     * Extract every bundled stub into [dir] (reusing an already-extracted copy) and return the jar paths.
     * Returns an empty list when the resources are absent. Cached per [dir] so repeated analyzer builds are
     * cheap; a missing file (cleared `.platform`) is re-extracted on the next call.
     */
    fun extractTo(dir: Path): List<Path> {
        val cached = extracted[dir]
        if (cached != null && cached.all { Files.isRegularFile(it) }) return cached
        val out = ArrayList<Path>()
        for (name in names()) {
            val target = dir.resolve(name)
            if (Files.isRegularFile(target) && Files.size(target) > 0L) {
                out.add(target)
                continue
            }
            val stream = BundledAndroidx::class.java.getResourceAsStream("$RESOURCE_DIR/$name") ?: continue
            runCatching {
                Files.createDirectories(dir)
                val tmp = Files.createTempFile(dir, name.removeSuffix(".jar"), ".tmp")
                stream.use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
                runCatching { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE) }
                    .getOrElse { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING) }
                target
            }.getOrNull()?.let { out.add(it) }
        }
        val result = out.toList()
        if (result.isNotEmpty()) extracted[dir] = result
        return result
    }
}
