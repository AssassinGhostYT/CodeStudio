package dev.ide.core

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * The desktop [TestRunner]: loads the compiled test classes with a `URLClassLoader` and hands each CLASS to
 * [JUnitReflectiveDriver] (JUnit's own runner over reflection), so `:ide-core` needs no compile-time dependency
 * on JUnit (which is a bundled resource, not a framework dependency).
 *
 * Parent is the bootstrap loader (null) — NOT the IDE's own classloader — so the test run sees exactly the
 * project's classes + the bundled JUnit, and a version of JUnit/Guava/… the IDE itself happens to use cannot
 * shadow the test's. A class that can't load is reported as one `ERROR` row rather than aborting the run.
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
            val work = Files.createTempDirectory("codestudio-junit")
            try {
                JUnitReflectiveDriver.run(loader, work, testClassNames, onOutcome)
            } finally {
                runCatching { work.toFile().deleteRecursively() }
            }
        }
    }
}
