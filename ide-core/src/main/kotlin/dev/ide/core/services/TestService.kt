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

            val testRoots = testSourceRoots(module)
            if (testRoots.isEmpty()) {
                return fail(
                    "No hay carpeta de tests. Crea ${module.name}/src/test/java (o src/test/kotlin) con una " +
                        "clase que tenga un método @Test (JUnit 4)."
                )
            }
            val javaTests = filesUnder(testRoots, ".java")
            val kotlinTests = filesUnder(testRoots, ".kt")
            if (javaTests.isEmpty() && kotlinTests.isEmpty()) {
                return fail("No se encontraron archivos .java/.kt en src/test.")
            }
            val testSources = (javaTests + kotlinTests).filter { looksLikeTest(it) }
            val testClassNames = testSources.mapNotNull { className(it) }.distinct()
            if (testClassNames.isEmpty()) {
                return fail("No se encontró ningún método @Test ni clase @RunWith en ${module.name}.")
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
            val bridge = writeTestBridge()
                ?: return fail("No se pudo preparar el runner de tests (no se pudo escribir el bridge).")
            val allJava = (filesUnder(mainRoots, ".java") + javaTests + bridge).distinct()
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
        // Gradle's JUnit discovery accepts a class with @Test, a custom @RunWith runner (suites, Parameterized,
        // Mockito/Robolectric), or a JUnit 3 TestCase subclass — mirror that so suites aren't silently skipped.
        return Regex("""@\s*(?:org\.junit\.)?Test\b""").containsMatchIn(text) ||
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
     * Writes the tiny `RunListener` the runner uses to report per-test events. It must be compiled WITH the
     * tests (JUnit is only on the test classpath, and the listener has to extend the test classloader's
     * `RunListener`), so it is added to the Java sources of this run. It appends tab-separated events to a log
     * file the runner tails — the IDE itself never depends on JUnit at compile time.
     */
    private fun writeTestBridge(): Path? {
        val dir = ctx.workspaceRoot.resolve(".platform/test-bridge/dev/ide/testbridge")
        return runCatching {
            Files.createDirectories(dir)
            val file = dir.resolve("CodeStudioJUnitBridge.java")
            // Only touch the file when the content actually changes: rewriting it every run would bump its
            // mtime and invalidate the incremental Kotlin compiler's context hash (a full recompile per run).
            if (!Files.exists(file) || Files.readString(file) != TEST_BRIDGE_SOURCE) {
                Files.writeString(file, TEST_BRIDGE_SOURCE)
            }
            file
        }.getOrNull()
    }

    private companion object {
        private val TEST_BRIDGE_SOURCE = """
package dev.ide.testbridge;

import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;

/** Generated by CodeStudio with the tests so it can extend the test classloader's JUnit RunListener. */
public final class CodeStudioJUnitBridge extends RunListener {
    private final Writer out;
    private long started;

    public CodeStudioJUnitBridge(String path) throws IOException {
        out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(path, true), "UTF-8"));
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
    }

    private void line(String s) {
        try { out.write(s); out.write('\n'); out.flush(); } catch (IOException ignored) {}
    }

    @Override public void testStarted(Description d) {
        started = System.currentTimeMillis();
    }

    @Override public void testFinished(Description d) {
        line("F\t" + clean(d.getClassName()) + "\t" + clean(d.getMethodName()) + "\t" + clean(d.getDisplayName())
            + "\t" + (System.currentTimeMillis() - started));
    }

    @Override public void testFailure(Failure f) {
        Description d = f.getDescription();
        line("X\t" + clean(d.getClassName()) + "\t" + clean(d.getMethodName()) + "\t" + clean(d.getDisplayName())
            + "\t" + clean(f.getMessage()) + "\t" + clean(f.getTrace()));
    }

    @Override public void testAssumptionFailure(Failure f) {
        Description d = f.getDescription();
        line("A\t" + clean(d.getClassName()) + "\t" + clean(d.getMethodName()) + "\t" + clean(d.getDisplayName())
            + "\t" + clean(f.getMessage()));
    }

    @Override public void testIgnored(Description d) {
        line("I\t" + clean(d.getClassName()) + "\t" + clean(d.getMethodName()) + "\t" + clean(d.getDisplayName()) + "\t");
    }
}
""".trimIndent()
    }
}
