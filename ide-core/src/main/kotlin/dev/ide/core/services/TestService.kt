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
import dev.ide.model.PlatformKind
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
 * sources for a run. Scoped to JVM modules for now (Android unit tests need the SDK/Robolectric path).
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

    fun run(moduleName: String) {
        if (_state.value.running) return
        _state.value = UiTestState(running = true, moduleName = moduleName, message = "Buscando tests…")
        scope.launch(Dispatchers.Default) { execute(moduleName) }
    }

    private fun execute(moduleName: String) {
        val started = System.currentTimeMillis()
        try {
            ctx.flushOpenDocuments()
            val module = ctx.modules().firstOrNull { it.name == moduleName }
                ?: return fail("No existe el módulo '$moduleName'.")
            if (module.type.platform != PlatformKind.JVM) {
                return fail("Los tests solo están soportados en módulos JVM (Java/Kotlin) por ahora.")
            }

            val testRoots = testSourceRoots(module)
            if (testRoots.isEmpty()) {
                return fail("No hay carpeta de tests. Crea src/test/java o src/test/kotlin en ${module.name}.")
            }
            val javaTests = filesUnder(testRoots, ".java")
            val kotlinTests = filesUnder(testRoots, ".kt")
            if (javaTests.isEmpty() && kotlinTests.isEmpty()) {
                return fail("No se encontraron archivos .java/.kt en src/test.")
            }
            val testSources = (javaTests + kotlinTests).filter { looksLikeTest(it) }
            val testClassNames = testSources.mapNotNull { className(it) }.distinct()
            if (testClassNames.isEmpty()) {
                return fail("No se encontró ningún método @Test en ${module.name}.")
            }

            val junitJars = BundledTestRuntime.extractTo(
                ctx.sharedCachesRoot?.resolve("test-runtime") ?: ctx.workspaceRoot.resolve(".platform/test-runtime")
            )
            if (junitJars.isEmpty()) return fail("No se encontró el runtime de JUnit empaquetado.")

            val moduleRoot = ctx.moduleRoot(module) ?: ctx.workspaceRoot
            val javaOut = moduleRoot.resolve("build/test-classes")
            val kotlinOut = moduleRoot.resolve("build/test-kotlin-classes")
            Files.createDirectories(javaOut)
            Files.createDirectories(kotlinOut)

            val mainRoots = mainSourceRoots(module)
            val allJava = (filesUnder(mainRoots, ".java") + javaTests).distinct()
            val allKotlin = (filesUnder(mainRoots, ".kt") + kotlinTests).distinct()

            val libs = module.classpath(DependencyScope.TEST_IMPLEMENTATION).entries
                .map { Paths.get(it.root.path) }
                .filter { Files.exists(it) }
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

            phase("Ejecutando ${testClassNames.size} clase(s) de test…")
            val results = ArrayList<UiTestResult>()
            val sourceByName = testSources.associateBy { it.fileName.toString() }
            runner.run(listOf(javaOut, kotlinOut) + libs, junitJars, testClassNames) { outcome ->
                results.add(outcome.toUi(sourceByName))
                publishResults(results, moduleName, started)
            }
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

    private fun looksLikeTest(file: Path): Boolean {
        val text = runCatching { Files.readString(file) }.getOrNull() ?: return false
        return Regex("""@\s*(?:org\.junit\.)?Test\b""").containsMatchIn(text)
    }

    private fun className(file: Path): String? {
        val text = runCatching { Files.readString(file) }.getOrNull() ?: return null
        val pkg = Regex("""(?m)^\s*package\s+([\w.]+)""").find(text)?.groupValues?.get(1)
        val simple = file.fileName.toString().substringBeforeLast('.')
        return if (pkg.isNullOrBlank()) simple else "$pkg.$simple"
    }
}
