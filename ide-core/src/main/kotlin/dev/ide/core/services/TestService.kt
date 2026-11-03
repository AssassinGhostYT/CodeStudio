package dev.ide.core.services

import dev.ide.build.engine.levelOf
import dev.ide.core.EngineContext
import dev.ide.core.TestOutcome
import dev.ide.core.TestOutcomeStatus
import dev.ide.core.TestRunner
import dev.ide.lang.jdt.compile.JdtBatchCompiler
import dev.ide.lang.kotlin.compile.IncrementalKotlinCompiler
import dev.ide.lang.kotlin.compile.KotlinCompilerPlugin
import dev.ide.lang.kotlin.compile.resolveFor
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.Module
import dev.ide.ui.backend.UiTestResult
import dev.ide.ui.backend.UiTestState
import dev.ide.ui.backend.UiTestStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

/**
 * Discovers, compiles and runs a JVM module's JUnit tests (`src/test/java`, `src/test/kotlin`) and streams
 * the results to the "Tests" console tab.
 *
 * Self-contained: it does NOT add a test task to the build graph. It compiles the module's main + test
 * sources into `build/test-{classes,kotlin-classes}` (so a test run doesn't depend on a prior build), then
 * hands the classpath to the host [TestRunner] (URLClassLoader on desktop, DexClassLoader on device). This
 * keeps normal builds untouched — the biggest risk of a test runner — at the cost of recompiling the main
 * sources for a run. Works for JVM and Android modules (Android runs against the device SDK; tests that need
 * a `Context`/Activity require instrumentation, not this runner).
 */
internal class TestService(
    private val ctx: EngineContext,
    private val incrementalKotlin: IncrementalKotlinCompiler,
    private val kotlinPlugins: List<KotlinCompilerPlugin>,
    private val runner: TestRunner,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(UiTestState())
    val state: StateFlow<UiTestState> = _state.asStateFlow()

    fun clear() {
        if (_state.value.running) return
        _state.value = UiTestState()
    }

    fun run(moduleName: String, onlyFailed: Boolean = false) {
        if (_state.value.running) return
        // Rerun-failed (Gradle's `--tests` at class granularity): keep only the classes that had a failed or
        // errored test in the previous run. No failures → nothing to do.
        val failedSuites = _state.value.results
            .filter { it.status == UiTestStatus.Failed || it.status == UiTestStatus.Error }
            .map { it.suite }
            .toSet()
        if (onlyFailed && failedSuites.isEmpty()) {
            _state.update { it.copy(message = "No hay tests fallidos para reintentar.") }
            return
        }
        _state.value = UiTestState(
            running = true,
            moduleName = moduleName,
            message = if (onlyFailed) "Reintentando ${failedSuites.size} clase(s) fallida(s)…" else "Buscando tests…",
        )
        scope.launch(Dispatchers.Default) { execute(moduleName, failedSuites) }
    }

    private fun execute(moduleName: String, onlySuites: Set<String> = emptySet()) {
        val started = System.currentTimeMillis()
        try {
            ctx.flushOpenDocuments()
            val module = ctx.modules().firstOrNull { it.name == moduleName }
                ?: return fail("No existe el módulo '$moduleName'.")

            // Dart/Flutter tests run through their own toolchain and JSON reporters, not the JVM runner.
            if (module.type.id == "dart-console" || module.type.id == "flutter-app") {
                return executeDart(module, onlySuites, started)
            }

            val testRoots = testSourceRoots(module)
            if (testRoots.isEmpty()) {
                if (androidTestsPresent(module)) return fail(ANDROID_TEST_UNSUPPORTED)
                return fail(
                    "No hay carpeta de tests. Crea ${module.name}/src/test/java (o src/test/kotlin) con una " +
                        "clase que tenga un método @Test (JUnit 4/5)."
                )
            }
            val javaTests = filesUnder(testRoots, ".java")
            val kotlinTests = filesUnder(testRoots, ".kt")
            if (javaTests.isEmpty() && kotlinTests.isEmpty()) {
                return fail("No se encontraron archivos .java/.kt en src/test.")
            }
            val testSources = (javaTests + kotlinTests).filter { looksLikeTest(it) }
            val testClassNames = testSources.mapNotNull { className(it) }
                .distinct()
                // Rerun-failed is class granularity: a nested/parameterized failure reports `Outer$Inner`, but
                // the selectable unit is `Outer`, so keep the outer class when any of its nested suites failed.
                .filter { onlySuites.isEmpty() || onlySuites.any { s -> s == it || s.startsWith(it + "$") } }
            if (testClassNames.isEmpty()) {
                if (androidTestsPresent(module)) return fail(ANDROID_TEST_UNSUPPORTED)
                return fail(
                    if (onlySuites.isEmpty()) "No se encontró ningún test JUnit (4 o 5) en ${module.name}."
                    else "No se encontraron las clases fallidas de la corrida anterior."
                )
            }

            val libs = module.classpath(DependencyScope.TEST_IMPLEMENTATION).entries
                .map { Paths.get(it.root.path) }
                .filter { Files.exists(it) }

            val runtimeDir = ctx.sharedCachesRoot?.resolve("test-runtime")
                ?: ctx.workspaceRoot.resolve(".platform/test-runtime")
            val junitJars = BundledTestRuntime.extractTo(runtimeDir).toMutableList()
            if (junitJars.isEmpty()) return fail("No se encontró el runtime de JUnit empaquetado.")
            // JUnit 5 is selected when the project declares a Jupiter/Platform/Vintage artifact OR a test
            // source imports/annotates JUnit 5. The standalone jar brings the launcher too (the `junit-jupiter`
            // aggregate does not), and its Vintage engine runs any JUnit 4 classes in the same pass.
            if (usesJUnit5(testSources, libs)) {
                val platform = BundledTestRuntime.extractJUnit5To(runtimeDir)
                    ?: return fail("Este proyecto usa JUnit 5 pero no se encontró el runtime de JUnit 5 empaquetado.")
                junitJars.add(platform)
            }

            val moduleRoot = ctx.moduleRoot(module) ?: ctx.workspaceRoot
            val javaOut = moduleRoot.resolve("build/test-classes")
            val kotlinOut = moduleRoot.resolve("build/test-kotlin-classes")
            Files.createDirectories(javaOut)
            Files.createDirectories(kotlinOut)

            val mainRoots = mainSourceRoots(module)
            // Test sources only. The JUnit bridge is a bundled `.class` (see [installTestBridge]): it is
            // compiled against the bundled JUnit and copied into the output, so it never goes through the
            // Kotlin/JDT compilers (and a directory can never reach kotlinc as a Java source root, which is
            // what NPE'd on Android).
            val allJava = (filesUnder(mainRoots, ".java") + javaTests)
                .filter { Files.isRegularFile(it) }
                .distinct()
            val allKotlin = (filesUnder(mainRoots, ".kt") + kotlinTests)
                .filter { Files.isRegularFile(it) }
                .distinct()

            val boot = ctx.bootClasspathFor(module)

            if (allKotlin.isNotEmpty()) {
                phase("Compilando Kotlin de tests…")
                val resolved = kotlinPlugins.resolveFor(module, libs + junitJars + boot)
                val r = incrementalKotlin.compile(
                    allKotlin, allJava, libs + junitJars, kotlinOut, levelOf(module.languageLevel),
                    bootClasspath = boot,
                    compilerPlugins = resolved.classpaths,
                    pluginOptions = resolved.options,
                    runtimePluginClasspaths = resolved.runtimeClasspaths,
                )
                if (!r.success) return fail("Error compilando Kotlin:\n" + r.messages.joinToString("\n"))
            }
            if (allJava.isNotEmpty()) {
                phase("Compilando Java de tests…")
                val r = JdtBatchCompiler.compile(
                    allJava, libs + junitJars + listOf(kotlinOut), javaOut, levelOf(module.languageLevel), bootClasspath = boot,
                )
                if (!r.success) return fail("Error compilando Java:\n" + r.messages.joinToString("\n"))
            }
            if (!installTestBridge(javaOut)) {
                return fail("No se pudo preparar el runner de tests (falta el bridge empaquetado).")
            }

            phase("Ejecutando ${testClassNames.size} clase(s) de test…")
            val results = ArrayList<UiTestResult>()
            val sourceByName = testSources.associateBy { it.fileName.toString() }
            runner.run(listOf(javaOut, kotlinOut) + libs, junitJars, testClassNames) { outcome ->
                results.add(outcome.toUi(sourceByName))
                publishResults(results, moduleName, started)
            }
            // Gradle/Studio parity: leave the JUnit XML under build/test-results/test (CI/tooling) and an HTML
            // report under build/reports/tests/test (humans). Best-effort — a report failure must not fail
            // the test run.
            runCatching { writeJUnitXml(results, moduleRoot.resolve("build/test-results/test")) }
            runCatching { writeHtmlReport(results, moduleRoot.resolve("build/reports/tests/test")) }
            _state.update {
                it.copy(
                    running = false,
                    elapsedMs = System.currentTimeMillis() - started,
                    message = if (results.isEmpty()) "No se ejecutó ningún test." else "",
                )
            }
        } catch (t: Throwable) {
            fail("Fallo del runner: ${t.message ?: t}")
        }
    }

    /** Runs a Dart/Flutter module's tests and streams results into the same Tests-tab state as JUnit. */
    private fun executeDart(module: Module, onlySuites: Set<String>, started: Long) {
        val results = ArrayList<UiTestResult>()
        val error = DartTestRunner(ctx).run(
            module = module,
            onlySuites = onlySuites,
            onResult = { result ->
                results.add(result)
                publishResults(results, module.name, started)
            },
            phase = { phase(it) },
        )
        if (error.isNotEmpty()) return fail(error)
        val moduleRoot = ctx.moduleRoot(module) ?: ctx.workspaceRoot
        runCatching { writeJUnitXml(results, moduleRoot.resolve("build/test-results/test")) }
        runCatching { writeHtmlReport(results, moduleRoot.resolve("build/reports/tests/test")) }
        _state.update {
            it.copy(
                running = false,
                elapsedMs = System.currentTimeMillis() - started,
                message = if (results.isEmpty()) "No se ejecutó ningún test." else "",
            )
        }
    }

    /** Writes one Gradle-style `TEST-<suite>.xml` per suite (the format Studio/CI parsers read). */
    private fun writeJUnitXml(results: List<UiTestResult>, dir: Path) {
        if (results.isEmpty()) return
        Files.createDirectories(dir)
        results.groupBy { it.suite.ifBlank { "tests" } }.forEach { (suite, cases) ->
            val failures = cases.count { it.status == UiTestStatus.Failed }
            val errors = cases.count { it.status == UiTestStatus.Error }
            val skipped = cases.count { it.status == UiTestStatus.Skipped }
            val time = cases.sumOf { it.durationMs } / 1000.0
            val xml = buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                append(
                    "<testsuite name=\"${xmlEscape(suite)}\" tests=\"${cases.size}\" failures=\"$failures\" " +
                        "errors=\"$errors\" skipped=\"$skipped\" time=\"$time\">\n"
                )
                cases.forEach { c ->
                    append("  <testcase classname=\"${xmlEscape(suite)}\" name=\"${xmlEscape(c.name)}\" time=\"${c.durationMs / 1000.0}\"")
                    when (c.status) {
                        UiTestStatus.Failed -> append(
                            ">\n    <failure message=\"${xmlEscape(c.message)}\">${xmlEscape(c.stackTrace)}</failure>\n  </testcase>\n"
                        )
                        UiTestStatus.Error -> append(
                            ">\n    <error message=\"${xmlEscape(c.message)}\">${xmlEscape(c.stackTrace)}</error>\n  </testcase>\n"
                        )
                        UiTestStatus.Skipped -> append(">\n    <skipped/>\n  </testcase>\n")
                        else -> append("/>\n")
                    }
                }
                append("</testsuite>\n")
            }
            val safe = suite.replace(Regex("""[^\w.\-]"""), "_")
            Files.writeString(dir.resolve("TEST-$safe.xml"), xml)
        }
    }

    /** Writes a self-contained `index.html` (Gradle/Studio-style report) with the run's results. */
    private fun writeHtmlReport(results: List<UiTestResult>, dir: Path) {
        Files.createDirectories(dir)
        val total = results.size
        val passed = results.count { it.status == UiTestStatus.Passed }
        val failed = results.count { it.status == UiTestStatus.Failed || it.status == UiTestStatus.Error }
        val skipped = results.count { it.status == UiTestStatus.Skipped }
        val html = buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Test results</title>")
            append("<style>body{font-family:sans-serif;margin:24px;color:#222}")
            append("table{border-collapse:collapse;width:100%}th,td{text-align:left;padding:6px 10px;border-bottom:1px solid #ddd}")
            append(".pass{color:#1a7f37}.fail{color:#b42318}.skip{color:#8a6d3b}pre{white-space:pre-wrap;margin:4px 0 0;color:#b42318;font-size:12px}")
            append("</style></head><body>")
            append("<h1>Tests</h1><p><b>$total</b> tests &middot; <span class=\"pass\">$passed passed</span> &middot; ")
            append("<span class=\"fail\">$failed failed</span> &middot; <span class=\"skip\">$skipped skipped</span></p>")
            append("<table><thead><tr><th>Class</th><th>Test</th><th>Status</th><th>Time</th></tr></thead><tbody>")
            results.sortedWith(compareBy({ it.suite }, { it.name })).forEach { r ->
                val (cls, label) = when (r.status) {
                    UiTestStatus.Passed -> "pass" to "PASSED"
                    UiTestStatus.Failed -> "fail" to "FAILED"
                    UiTestStatus.Error -> "fail" to "ERROR"
                    UiTestStatus.Skipped -> "skip" to "SKIPPED"
                }
                append("<tr><td>${htmlEscape(r.suite)}</td><td>${htmlEscape(r.name)}</td>")
                append("<td class=\"$cls\">$label</td><td>${r.durationMs} ms</td></tr>")
                if (r.status == UiTestStatus.Failed || r.status == UiTestStatus.Error) {
                    val detail = listOf(r.message, r.stackTrace).filter { it.isNotBlank() }.joinToString("\n")
                    if (detail.isNotBlank()) {
                        append("<tr><td colspan=\"4\"><pre>${htmlEscape(detail)}</pre></td></tr>")
                    }
                }
            }
            append("</tbody></table></body></html>")
        }
        Files.writeString(dir.resolve("index.html"), html)
    }

    private fun htmlEscape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun publishResults(results: List<UiTestResult>, moduleName: String, started: Long) {
        _state.update {
            it.copy(
                running = true,
                moduleName = moduleName,
                results = results.toList(),
                total = results.size,
                passed = results.count { r -> r.status == UiTestStatus.Passed },
                failed = results.count { r -> r.status == UiTestStatus.Failed || r.status == UiTestStatus.Error },
                skipped = results.count { r -> r.status == UiTestStatus.Skipped },
                elapsedMs = System.currentTimeMillis() - started,
            )
        }
    }

    private fun phase(message: String) = _state.update { it.copy(running = true, message = message) }

    private fun fail(message: String) {
        _state.update { it.copy(running = false, message = message, elapsedMs = it.elapsedMs) }
    }

    private fun TestOutcome.toUi(sourceByName: Map<String, Path>): UiTestResult {
        var file: String? = null
        var line = -1
        Regex("""\(([\w$]+\.(?:java|kt)):(\d+)\)""").find(stackTrace)?.let { m ->
            file = sourceByName[m.groupValues[1]]?.toString()
            line = m.groupValues[2].toIntOrNull() ?: -1
        }
        return UiTestResult(
            suite = suite,
            name = name,
            status = when (status) {
                TestOutcomeStatus.PASSED -> UiTestStatus.Passed
                TestOutcomeStatus.FAILED -> UiTestStatus.Failed
                TestOutcomeStatus.SKIPPED -> UiTestStatus.Skipped
                TestOutcomeStatus.ERROR -> UiTestStatus.Error
            },
            durationMs = durationMs,
            message = message,
            stackTrace = stackTrace,
            file = file,
            line = line,
        )
    }

    /** Test source roots from the model, falling back to the conventional on-disk `src/test` dirs (a
     *  scaffolded project has no `test` source set in its model, only the folder). */
    private fun testSourceRoots(module: Module): List<Path> {
        val fromModel = module.sourceSets
            .filter { it.scope == DependencyScope.TEST_IMPLEMENTATION }
            .flatMap { it.contentRoots }
            .filter { ContentRole.SOURCE in it.roles || ContentRole.GENERATED in it.roles }
            .map { Paths.get(it.dir.path) }
            .filter { Files.isDirectory(it) }
        if (fromModel.isNotEmpty()) return fromModel
        val root = ctx.moduleRoot(module) ?: return emptyList()
        return listOf(root.resolve("src/test/java"), root.resolve("src/test/kotlin"))
            .filter { Files.isDirectory(it) }
    }

    private fun mainSourceRoots(module: Module): List<Path> = module.sourceSets
        .filter { it.scope != DependencyScope.TEST_IMPLEMENTATION }
        .flatMap { it.contentRoots }
        .filter { ContentRole.SOURCE in it.roles || ContentRole.GENERATED in it.roles }
        .map { Paths.get(it.dir.path) }
        .filter { Files.isDirectory(it) }

    private fun filesUnder(roots: List<Path>, extension: String): List<Path> =
        roots.flatMap { root ->
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.toString().endsWith(extension) }
                    .collect(Collectors.toList())
            }
        }.distinct()

    /** True when `src/androidTest` exists and holds a JUnit test, so the empty-run message can explain why. */
    private fun androidTestsPresent(module: Module): Boolean {
        val root = ctx.moduleRoot(module)?.resolve("src/androidTest") ?: return false
        if (!Files.isDirectory(root)) return false
        val files = runCatching {
            filesUnder(listOf(root), ".java") + filesUnder(listOf(root), ".kt")
        }.getOrDefault(emptyList())
        return files.any { looksLikeTest(it) }
    }

    private val ANDROID_TEST_UNSUPPORTED =
        "Hay tests instrumentados en src/androidTest, pero necesitan un dispositivo/emulador y todavía no se " +
            "ejecutan desde la pestaña Tests. Los tests JUnit 4/5 de src/test sí se ejecutan aquí."

    private val JUNIT5_HINTS = listOf(
        Regex("""org\.junit\.(?:jupiter|platform|vintage)"""),
        Regex(
            """@\s*(?:TestFactory|ParameterizedTest|Nested|ExtendWith|DisplayName|RepeatedTest|TestInstance|""" +
                """TestTemplate|RegisterExtension|BeforeEach|AfterEach|BeforeAll|AfterAll|Disabled|Suite|""" +
                """SelectClasses|SelectPackages|SelectClasspathRoots)\b"""
        ),
    )

    /**
     * Whether this run should go through the JUnit Platform. A declared Jupiter/Platform/Vintage (or
     * `kotlin-test-junit5`) artifact is authoritative; otherwise a test source importing `org.junit.jupiter`
     * / `org.junit.platform` or using a JUnit-5-only annotation is enough. Plain `@Test` stays JUnit 4 (its
     * import decides), so existing projects keep the lighter JUnit 4 path.
     */
    private fun usesJUnit5(sources: List<Path>, libs: List<Path>): Boolean {
        val junit5Artifact = { p: Path ->
            val n = p.fileName?.toString()?.lowercase().orEmpty()
            n.contains("junit-jupiter") || n.contains("junit-platform") ||
                n.contains("junit-vintage") || n.contains("kotlin-test-junit5")
        }
        if (libs.any(junit5Artifact)) return true
        return sources.any { file ->
            val text = runCatching { Files.readString(file) }.getOrNull() ?: return@any false
            JUNIT5_HINTS.any { it.containsMatchIn(text) }
        }
    }

    private fun looksLikeTest(file: Path): Boolean {
        val text = runCatching { Files.readString(file) }.getOrNull() ?: return false
        // Gradle's JUnit discovery accepts a class with @Test, a custom @RunWith runner (suites, Parameterized,
        // Mockito/Robolectric), or a JUnit 3 TestCase subclass — mirror that so suites aren't silently skipped.
        return Regex("""@\s*(?:(?:org\.junit|org\.junit\.jupiter\.api)\.)?Test\b""").containsMatchIn(text) ||
            Regex(
                """@\s*(?:(?:org\.junit\.jupiter\.(?:api|params))\.)?(?:ParameterizedTest|TestFactory|RepeatedTest|TestTemplate)\b"""
            ).containsMatchIn(text) ||
            Regex(
                """@\s*(?:org\.junit\.platform\.suite\.api\.)?(?:Suite|SelectClasses|SelectPackages|SelectClasspathRoots)\b"""
            ).containsMatchIn(text) ||
            Regex("""@\s*(?:org\.junit\.runner\.)?RunWith\b""").containsMatchIn(text) ||
            Regex("""\bextends\s+(?:junit\.framework\.)?TestCase\b""").containsMatchIn(text)
    }

    private fun className(file: Path): String? {
        val text = runCatching { Files.readString(file) }.getOrNull() ?: return null
        val pkg = Regex("""(?m)^\s*package\s+([\w.]+)""").find(text)?.groupValues?.get(1)
        val simple = file.fileName.toString().substringBeforeLast('.')
        return if (pkg.isNullOrBlank()) simple else "$pkg.$simple"
    }

    /**
     * Copies the tiny `RunListener` bridge into the compiled test classes. It is bundled as a `.class`
     * (compiled against the bundled JUnit 4.13.2), so it never has to be compiled with the user's sources:
     * no Kotlin/JDT involvement, no Java source root, no ClassNotFoundException on ART.
     */
    private fun installTestBridge(javaOut: Path): Boolean = runCatching {
        val rel = "dev/ide/testbridge/CodeStudioJUnitBridge.class"
        // Stored with a `.bin` suffix so build tooling never treats it as a compiled class of this module.
        val bytes = TestService::class.java.getResourceAsStream("/test-runtime/$rel.bin")?.use { it.readBytes() }
            ?: return false
        val dst = javaOut.resolve(rel)
        Files.createDirectories(dst.parent)
        if (!Files.isRegularFile(dst) || !Files.readAllBytes(dst).contentEquals(bytes)) {
            Files.write(dst, bytes)
        }
        true
    }.getOrDefault(false)
}
