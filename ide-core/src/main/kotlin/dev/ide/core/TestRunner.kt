package dev.ide.core

import java.nio.file.Path

/** Outcome of one test method, host-neutral (the engine maps it to the UI's `UiTestStatus`). */
enum class TestOutcomeStatus { PASSED, FAILED, SKIPPED, ERROR }

data class TestOutcome(
    val suite: String,
    val name: String,
    val status: TestOutcomeStatus,
    val durationMs: Long,
    val message: String = "",
    val stackTrace: String = "",
)

/**
 * Host port that actually loads and runs JUnit test classes. The engine compiles the test sources and hands
 * over the resulting classpath; where the classes are loaded differs by host:
 *  - desktop: a `URLClassLoader` over the class dirs + jars (the default, [JvmTestRunner]);
 *  - Android: a `DexClassLoader` over a D8-dexed archive (the `:ide-android` port), because ART can only
 *    define dex, not raw `.class` bytes.
 *
 * [junitClasspath] is the bundled JUnit + Hamcrest jars, kept separate from the test classpath so the runner
 * can isolate them (parent = bootstrap on the JVM) and so a project's own JUnit version never leaks in.
 * Blocking; the engine calls it off the UI thread. Emits one [TestOutcome] per method as it finishes.
 */
interface TestRunner {
    fun run(
        classpath: List<Path>,
        junitClasspath: List<Path>,
        testClassNames: List<String>,
        onOutcome: (TestOutcome) -> Unit,
    )
}
