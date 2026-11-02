package dev.ide.core

import java.nio.file.Files
import java.nio.file.Path

/**
 * Drives JUnit 4 reflectively over a [ClassLoader] that already sees the test classes and JUnit — the desktop
 * `URLClassLoader` ([JvmTestRunner]) or ART's `DexClassLoader` (the `:ide-android` port).
 *
 * Each test CLASS is handed to JUnit's own runner (`Request.aClass`), not invoked method by method, so custom
 * runners (`@RunWith` — Mockito, Robolectric, `Parameterized`, suites, …), `@BeforeClass`/`@AfterClass` (once
 * per class) and `@Rule`s behave exactly as under Gradle/Android Studio.
 *
 * Per-test events come from a tiny [BRIDGE] class compiled WITH the tests (see `TestService`): it extends
 * JUnit's `RunListener` in the test classloader and appends one tab-separated line per event to a log file,
 * which this driver tails after each class. That keeps the IDE free of a compile-time JUnit dependency while
 * still reporting real pass/fail/skip statuses and per-test durations.
 */
object JUnitReflectiveDriver {
    const val BRIDGE = "dev.ide.testbridge.CodeStudioJUnitBridge"

    fun run(
        loader: ClassLoader,
        workDir: Path,
        testClassNames: List<String>,
        onOutcome: (TestOutcome) -> Unit,
    ) {
        // A project that brought the JUnit Platform (JUnit 5, or a JUnit 5-aware Kotlin test dependency) is
        // driven by the Platform launcher: it also runs any JUnit 4 classes through the Vintage engine, so a
        // mixed suite is handled in one pass. Otherwise keep the JUnit 4 path below.
        if (JUnit5ReflectiveDriver.isAvailable(loader)) {
            JUnit5ReflectiveDriver.run(loader, testClassNames, onOutcome)
            return
        }
        val junitCoreClass = loader.loadClass("org.junit.runner.JUnitCore")
        val requestClass = loader.loadClass("org.junit.runner.Request")
        val listenerClass = loader.loadClass("org.junit.runner.notification.RunListener")
        val bridgeClass = loader.loadClass(BRIDGE)
        @Suppress("UNCHECKED_CAST")
        val testAnn = loader.loadClass("org.junit.Test") as Class<out Annotation>
        @Suppress("UNCHECKED_CAST")
        val runWithAnn = loader.loadClass("org.junit.runner.RunWith") as Class<out Annotation>

        val core = junitCoreClass.getDeclaredConstructor().newInstance()
        val addListener = junitCoreClass.getMethod("addListener", listenerClass)
        val runRequest = junitCoreClass.getMethod("run", requestClass)
        val aClass = requestClass.getMethod("aClass", Class::class.java)

        Files.createDirectories(workDir)
        val log = Files.createTempFile(workDir, "junit-", ".log")
        try {
            val bridge = bridgeClass.getConstructor(String::class.java).newInstance(log.toString())
            addListener.invoke(core, bridge)

            var processed = 0
            val pendingFailures = LinkedHashMap<String, Pair<String, String>>()
            val assumed = HashSet<String>()

            fun emit(
                cls: String,
                method: String,
                status: TestOutcomeStatus,
                duration: Long,
                message: String = "",
                trace: String = "",
            ) = onOutcome(
                TestOutcome(
                    cls.ifEmpty { "<test>" }, method.ifEmpty { "<test>" }, status, duration, message, trace,
                )
            )

            fun drain() {
                val lines = runCatching { Files.readAllLines(log) }.getOrDefault(emptyList())
                while (processed < lines.size) {
                    val parts = lines[processed].split('\t')
                    processed++
                    when (parts.getOrNull(0)) {
                        "F" -> {
                            val cls = parts.getOrNull(1).orEmpty()
                            val method = parts.getOrNull(2).orEmpty()
                            val display = parts.getOrNull(3).orEmpty()
                            val duration = parts.getOrNull(4)?.toLongOrNull() ?: 0
                            val failure = pendingFailures.remove(display)
                            val status = when {
                                failure != null -> TestOutcomeStatus.FAILED
                                display in assumed -> TestOutcomeStatus.SKIPPED
                                else -> TestOutcomeStatus.PASSED
                            }
                            assumed.remove(display)
                            val (msg, trace) = failure ?: ("" to "")
                            emit(cls, method, status, duration, msg, trace)
                        }
                        "X" -> {
                            val display = parts.getOrNull(3).orEmpty()
                            pendingFailures[display] =
                                unescape(parts.getOrNull(4).orEmpty()) to unescape(parts.getOrNull(5).orEmpty())
                        }
                        "A" -> assumed.add(parts.getOrNull(3).orEmpty())
                        "I" -> emit(
                            parts.getOrNull(1).orEmpty(),
                            parts.getOrNull(2).orEmpty(),
                            TestOutcomeStatus.SKIPPED,
                            0,
                        )
                    }
                }
            }

            for (className in testClassNames) {
                val cls = try {
                    loader.loadClass(className)
                } catch (t: Throwable) {
                    emit(className, "<class>", TestOutcomeStatus.ERROR, 0, t.toString(), t.stackTraceToString())
                    continue
                }
                // A plain class with no @Test has nothing to run; a @RunWith class (suite, Parameterized, …) may
                // legitimately declare no @Test method of its own, so it is always handed to JUnit.
                if (cls.methods.none { it.isAnnotationPresent(testAnn) } && !cls.isAnnotationPresent(runWithAnn)) {
                    emit(className, "<no @Test>", TestOutcomeStatus.SKIPPED, 0, "No @Test methods")
                    continue
                }
                try {
                    runRequest.invoke(core, aClass.invoke(null, cls))
                } catch (t: Throwable) {
                    val cause = t.cause ?: t
                    emit(className, "<class>", TestOutcomeStatus.ERROR, 0, cause.toString(), cause.stackTraceToString())
                }
                drain()
                // A failure with no matching testFinished is a class-level (initialization / @AfterClass) error.
                val leftovers = pendingFailures.toList()
                pendingFailures.clear()
                for ((_, failure) in leftovers) {
                    emit(className, "<class>", TestOutcomeStatus.ERROR, 0, failure.first, failure.second)
                }
            }
        } finally {
            runCatching { Files.deleteIfExists(log) }
        }
    }

    /** Reverses the bridge's `clean` escaping (`\\`, `\t`, `\r`, `\n`). */
    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    else -> { sb.append(c); i++ }
                }
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }
}
