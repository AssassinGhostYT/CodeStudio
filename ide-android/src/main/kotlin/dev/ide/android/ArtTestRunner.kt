package dev.ide.android

import dalvik.system.DexClassLoader
import dev.ide.android.support.tools.ArtReflectionRewrite
import dev.ide.android.support.tools.D8InProcessDexer
import dev.ide.android.support.tools.DexInputDedup
import dev.ide.core.TestOutcome
import dev.ide.core.TestOutcomeStatus
import dev.ide.core.TestRunner
import java.lang.reflect.Method
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
        val junitCore = loader.loadClass("org.junit.runner.JUnitCore")
        val requestClass = loader.loadClass("org.junit.runner.Request")
        val resultClass = loader.loadClass("org.junit.runner.Result")
        val failureClass = loader.loadClass("org.junit.runner.notification.Failure")
        @Suppress("UNCHECKED_CAST")
        val testAnn = loader.loadClass("org.junit.Test") as Class<out Annotation>
        @Suppress("UNCHECKED_CAST")
        val ignoreAnn = loader.loadClass("org.junit.Ignore") as Class<out Annotation>

        val core = junitCore.getDeclaredConstructor().newInstance()
        val runRequest: Method = junitCore.getMethod("run", requestClass)
        val requestMethod: Method = requestClass.getMethod("method", Class::class.java, String::class.java)
        val getFailures: Method = resultClass.getMethod("getFailures")
        val getIgnoreCount: Method = resultClass.getMethod("getIgnoreCount")
        val getMessage: Method = failureClass.getMethod("getMessage")
        val getTrace: Method = failureClass.getMethod("getTrace")

        for (className in testClassNames) {
            val cls = try {
                loader.loadClass(className)
            } catch (t: Throwable) {
                onOutcome(TestOutcome(className, "<class>", TestOutcomeStatus.ERROR, 0, t.toString(), t.stackTraceToString()))
                continue
            }
            val methods = cls.methods.filter { it.isAnnotationPresent(testAnn) }
            if (methods.isEmpty()) {
                onOutcome(TestOutcome(className, "<no @Test>", TestOutcomeStatus.SKIPPED, 0, "No @Test methods"))
                continue
            }
            val classIgnored = cls.isAnnotationPresent(ignoreAnn)
            for (m in methods) {
                if (classIgnored || m.isAnnotationPresent(ignoreAnn)) {
                    onOutcome(TestOutcome(className, m.name, TestOutcomeStatus.SKIPPED, 0))
                    continue
                }
                val started = System.currentTimeMillis()
                try {
                    val request = requestMethod.invoke(null, cls, m.name)
                    val result = runRequest.invoke(core, request)
                    val elapsed = System.currentTimeMillis() - started
                    @Suppress("UNCHECKED_CAST")
                    val failures = getFailures.invoke(result) as List<Any>
                    if (failures.isEmpty()) {
                        val ignored = getIgnoreCount.invoke(result) as Int
                        val status = if (ignored > 0) TestOutcomeStatus.SKIPPED else TestOutcomeStatus.PASSED
                        onOutcome(TestOutcome(className, m.name, status, elapsed))
                    } else {
                        val failure = failures.first()
                        val message = getMessage.invoke(failure) as String? ?: ""
                        val trace = getTrace.invoke(failure) as String? ?: ""
                        onOutcome(TestOutcome(className, m.name, TestOutcomeStatus.FAILED, elapsed, message, trace))
                    }
                } catch (t: Throwable) {
                    val cause = t.cause ?: t
                    onOutcome(TestOutcome(className, m.name, TestOutcomeStatus.ERROR, System.currentTimeMillis() - started, cause.toString(), cause.stackTraceToString()))
                }
            }
        }
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
