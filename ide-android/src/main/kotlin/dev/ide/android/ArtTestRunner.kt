package dev.ide.android

import dalvik.system.DexClassLoader
import dev.ide.android.support.tools.ArtReflectionRewrite
import dev.ide.android.support.tools.D8InProcessDexer
import dev.ide.android.support.tools.DexInputDedup
import dev.ide.core.JUnitReflectiveDriver
import dev.ide.core.TestOutcome
import dev.ide.core.TestOutcomeStatus
import dev.ide.core.TestRunner
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.stream.Collectors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Runner de tests JUnit 4 para ART (Android). ART solo puede definir bytecode en formato DEX, así que:
 *   1. se empaquetan los directorios de clases (test-classes / kotlin-classes) en jars;
 *   2. D8-dexea el classpath completo (clases + dependencias + JUnit) en un `classes.dex`;
 *   3. se empaqueta el dex en un jar de solo lectura y se carga con un [DexClassLoader];
 *   4. se ejecuta JUnit 4 por reflexión (igual que [dev.ide.core.JvmTestRunner]).
 *
 * Reutiliza el mismo camino probado que `ArtKotlinPluginLoader` (D8 in-process + dedup + parche de
 * reflexión para ART). Si D8 falla, se reporta un `ERROR` en la pestaña Tests en vez de romper la app.
 */
class ArtTestRunner(
    private val androidJar: Path,
    private val cacheRoot: Path,
    private val minApi: Int,
) : TestRunner {

    override fun run(
        classpath: List<Path>,
        junitClasspath: List<Path>,
        testClassNames: List<String>,
        onOutcome: (TestOutcome) -> Unit,
    ) {
        try {
            Files.createDirectories(cacheRoot)
            val work = Files.createTempDirectory(cacheRoot, "tests-")
            try {
                // 1. Los directorios de clases se empaquetan en jars (D8 no acepta directorios).
                val dirJars = classpath.filter { Files.isDirectory(it) }.map { dir ->
                    val jar = work.resolve("dir-${hash(dir.toString())}.jar")
                    zipDir(dir, jar)
                    jar
                }
                val jars = (classpath.filter { Files.isRegularFile(it) } + junitClasspath.filter { Files.isRegularFile(it) } + dirJars)
                    .distinct()
                if (jars.isEmpty()) {
                    onOutcome(TestOutcome("<runner>", "classpath vacío", TestOutcomeStatus.ERROR, 0))
                    return
                }

                // 2. Parche de reflexión + dedup + D8.
                val artSafe = work.resolve("art-safe")
                Files.createDirectories(artSafe)
                val patched = ArtReflectionRewrite.patch(jars, artSafe)
                val program = DexInputDedup.firstWins(patched, artSafe.resolve("dedup"))
                val dexDir = work.resolve("dex")
                Files.createDirectories(dexDir)
                val dexResult = D8InProcessDexer().dex(
                    program, androidJar, minOf(minApi, 36), release = false, outDir = dexDir, threads = 0,
                    desugaredLibConfig = null,
                )
                if (!dexResult.success) {
                    onOutcome(TestOutcome("<runner>", "D8 falló:\n" + dexResult.log.joinToString("\n"), TestOutcomeStatus.ERROR, 0))
                    return
                }

                // 3. Empaquetar classes*.dex en un jar de solo lectura (ART 14+ rechaza jars escribibles).
                val dexJar = work.resolve("tests.jar")
                packageDex(dexDir, dexJar)
                val odex = work.resolve("odex")
                Files.createDirectories(odex)

                // 4. Cargar y ejecutar JUnit 4 por reflexión.
                val loader = DexClassLoader(dexJar.toString(), odex.toString(), null, null)
                runJUnit(loader, testClassNames, onOutcome)
            } finally {
                runCatching { work.toFile().deleteRecursively() }
            }
        } catch (t: Throwable) {
            onOutcome(TestOutcome("<runner>", "ArtTestRunner: ${t.message ?: t}", TestOutcomeStatus.ERROR, 0, t.stackTraceToString()))
        }
    }

    private fun runJUnit(loader: ClassLoader, testClassNames: List<String>, onOutcome: (TestOutcome) -> Unit) {
        // JUnit's own runner over the DexClassLoader: whole classes (honours @RunWith / @BeforeClass / rules),
        // with per-test results streamed from the bridge RunListener — shared with the desktop JvmTestRunner.
        JUnitReflectiveDriver.run(loader, cacheRoot, testClassNames, onOutcome)
    }

    private fun zipDir(dir: Path, jar: Path) {
        Files.newOutputStream(jar).use { os ->
            ZipOutputStream(os).use { zip ->
                Files.walk(dir).use { stream ->
                    for (file in stream.collect(Collectors.toList())) {
                        if (!Files.isRegularFile(file)) continue
                        val name = dir.relativize(file).toString().replace('\\', '/')
                        zip.putNextEntry(ZipEntry(name))
                        Files.copy(file, zip)
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    private fun packageDex(dexDir: Path, jar: Path) {
        val dexes = Files.list(dexDir).use { s ->
            s.filter { it.toString().endsWith(".dex") }.sorted().collect(Collectors.toList())
        }
        check(dexes.isNotEmpty()) { "D8 produjo ningún dex" }
        val tmp = Files.createTempFile(jar.parent, "tests", ".jar.tmp")
        try {
            ZipOutputStream(Files.newOutputStream(tmp)).use { zip ->
                for (dex in dexes) {
                    zip.putNextEntry(ZipEntry(dex.fileName.toString()))
                    Files.copy(dex, zip)
                    zip.closeEntry()
                }
            }
            tmp.toFile().setReadOnly()
            Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            runCatching { tmp.toFile().setWritable(true); Files.deleteIfExists(tmp) }
        }
    }

    private fun hash(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(s.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }.substring(0, 16)
    }
}
