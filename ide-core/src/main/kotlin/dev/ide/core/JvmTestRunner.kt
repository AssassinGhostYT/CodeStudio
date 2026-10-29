package dev.ide.core

import java.lang.reflect.Method
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * The desktop [TestRunner]: loads the compiled test classes with a `URLClassLoader` and drives JUnit 4
 * through reflection, so `:ide-core` needs no compile-time dependency on JUnit (which is a bundled resource,
 * not a framework dependency).
 *
 * Parent is the bootstrap loader (null) — NOT the IDE's own classloader — so the test run sees exactly the
 * project's classes + the bundled JUnit, and a version of JUnit/Guava/… the IDE itself happens to use cannot
 * shadow the test's. Each test method is run through `Request.method(...)` so its duration is accurate; a
 * class that can't load is reported as one `ERROR` row rather than aborting the run.
 */
class JvmTestRunner : TestRunner {
    override fun run(
        classpath: List<Path>,
        junitClasspath: List<Path>,
        testClassNames: List<String>,
        onOutcome: (TestOutcome) -> Unit,
    ) {
        val urls = (classpath + junitClasspath)
            .filter { Files.exists(it) }
            .map { it.toUri().toURL() }
            .toTypedArray()
        URLClassLoader(urls, null).use { loader ->
            val junitCore = loader.loadClass("org.junit.runner.JUnitCore")
            val requestClass = loader.loadClass("org.junit.runner.Request")
            val resultClass = loader.loadClass("org.junit.runner.Result")
            val failureClass = loader.loadClass("org.junit.runner.notification.Failure")
            val testAnn = loader.loadClass("org.junit.Test")
            val ignoreAnn = loader.loadClass("org.junit.Ignore")

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
                        onOutcome(
                            TestOutcome(
                                className, m.name, TestOutcomeStatus.ERROR,
                                System.currentTimeMillis() - started, cause.toString(), cause.stackTraceToString(),
                            )
                        )
                    }
                }
            }
        }
    }
}
