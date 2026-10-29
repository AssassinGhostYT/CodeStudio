package dev.ide.ui.backend

/** Outcome of one unit-test method, as shown in the build console's Tests tab. */
enum class UiTestStatus { Passed, Failed, Skipped, Error }

/**
 * One executed test method. [suite] is the test class's fully-qualified name and [name] the method; a
 * suite-level problem (the class failed to load, no `@Test` methods) is reported as a single row with a
 * synthetic [name] such as `<class>`. [message]/[stackTrace] carry the failure detail (empty when passed).
 */
data class UiTestResult(
    val suite: String,
    val name: String,
    val status: UiTestStatus,
    val durationMs: Long = 0,
    val message: String = "",
    val stackTrace: String = "",
    val file: String? = null,
    val line: Int = -1,
)

/**
 * Live state of a JUnit run for the "Tests" console tab: whether it is [running], which [moduleName] it
 * targets, the per-test [results] streamed so far, the aggregate counts, and a status [message] (a phase
 * like "Compilando tests…" while running, or the reason a run couldn't start).
 */
data class UiTestState(
    val running: Boolean = false,
    val moduleName: String = "",
    val results: List<UiTestResult> = emptyList(),
    val total: Int = 0,
    val passed: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val elapsedMs: Long = 0,
    val message: String = "",
) {
    /** True once a run has produced something worth showing (so the tab can distinguish "empty" from "never run"). */
    val hasContent: Boolean get() = total > 0 || message.isNotEmpty() || moduleName.isNotEmpty()
}
