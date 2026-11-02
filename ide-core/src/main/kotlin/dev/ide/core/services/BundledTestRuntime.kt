package dev.ide.core.services

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Supplies the JUnit 4 runtime (`junit` + its `hamcrest-core` dependency) WITHOUT a network resolve, so a
 * user's tests compile and run offline — the same idea as `BundledAndroidx`, for the test runtime instead of
 * the editor classpath.
 *
 * The jars are bundled as classpath resources under `/test-runtime/` and extracted to a real directory on
 * demand (cached per destination), because both the Java compiler's classpath and the class loaders that run
 * the tests need file paths.
 */
object BundledTestRuntime {
    private const val RESOURCE_DIR = "/test-runtime"

    /** Bundled jars, in a stable order (JUnit first, then its Hamcrest dependency). */
    private val NAMES = listOf("junit-4.13.2.jar", "hamcrest-core-1.3.jar")

    /**
     * JUnit 5 (Jupiter) + the Platform launcher and the Vintage engine, as the official standalone fat jar.
     * It is extracted only when a project actually uses JUnit 5, so JUnit 4-only runs don't pay the dex cost.
     * Multi-release/`module-info` entries were stripped when bundling: D8 would see the Java 9+ duplicates as
     * the same classes and fail with a duplicate-class error, and the base (Java 8) classes are enough here.
     */
    private const val JUNIT5_JAR = "junit-platform-console-standalone-1.10.2.jar"

    private val extracted = ConcurrentHashMap<Path, List<Path>>()

    /** True when the jars are bundled on the classpath (false only in a stripped-down test classpath). */
    fun isBundled(): Boolean = BundledTestRuntime::class.java.getResource("$RESOURCE_DIR/${NAMES.first()}") != null

    /** True when the JUnit 5 standalone jar is bundled (it always is in normal builds). */
    fun isJUnit5Bundled(): Boolean =
        BundledTestRuntime::class.java.getResource("$RESOURCE_DIR/$JUNIT5_JAR") != null

    /** The bundled jar file names, in stable order. */
    fun names(): List<String> = NAMES

    /** Extract every bundled jar into [dir] (reusing an already-extracted copy) and return their paths. */
    fun extractTo(dir: Path): List<Path> {
        val cached = extracted[dir]
        if (cached != null && cached.all { Files.isRegularFile(it) }) return cached
        val out = ArrayList<Path>()
        for (name in NAMES) {
            val target = dir.resolve(name)
            if (Files.isRegularFile(target) && Files.size(target) > 0L) {
                out.add(target)
                continue
            }
            val stream = BundledTestRuntime::class.java.getResourceAsStream("$RESOURCE_DIR/$name") ?: continue
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

    /**
     * Extract the JUnit 5 standalone jar into [dir] (reusing an already-extracted copy) and return its path.
     * Kept separate from [extractTo] so JUnit 4-only projects never ship the ~2.7 MB platform to the dexer.
     */
    fun extractJUnit5To(dir: Path): Path? {
        val target = dir.resolve(JUNIT5_JAR)
        if (Files.isRegularFile(target) && Files.size(target) > 0L) return target
        val stream = BundledTestRuntime::class.java.getResourceAsStream("$RESOURCE_DIR/$JUNIT5_JAR") ?: return null
        return runCatching {
            Files.createDirectories(dir)
            val tmp = Files.createTempFile(dir, "junit5", ".tmp")
            stream.use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
            runCatching { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE) }
                .getOrElse { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING) }
            target
        }.getOrNull()
    }
}
