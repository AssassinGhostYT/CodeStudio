package dev.ide.core

import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Drives JUnit 5 (Jupiter) over a [ClassLoader] that sees the tests and the JUnit Platform — the desktop
 * `URLClassLoader` ([JvmTestRunner]) or ART's `DexClassLoader` (`:ide-android`).
 *
 * It uses the public Platform `Launcher` API (so `@Nested`, `@ParameterizedTest`, `@TestFactory`,
 * `@ExtendWith`, `@BeforeAll`/`@AfterAll`, … behave as under Gradle) but every class is reached by
 * reflection, keeping this module free of a compile-time JUnit dependency. The listener is a
 * [Proxy] over the `TestExecutionListener` interface (an interface, unlike JUnit 4's `RunListener` class),
 * so the bridge-file trick isn't needed here.
 *
 * The engines are registered EXPLICITLY and auto-registration is disabled: on Android the classes are
 * repackaged into a plain `.dex` jar, where `META-INF/services` is lost, so `ServiceLoader` would find no
 * engine. Jupiter + Vintage are added directly, which also lets one run cover mixed JUnit 5/4 suites.
 */
object JUnit5ReflectiveDriver {
    private const val LAUNCHER_FACTORY = "org.junit.platform.launcher.core.LauncherFactory"
    private const val JUPITER_ENGINE = "org.junit.jupiter.engine.JupiterTestEngine"
    private const val VINTAGE_ENGINE = "org.junit.vintage.engine.VintageTestEngine"

    /** True when [loader] can see a Platform launcher and the Jupiter engine. */
    fun isAvailable(loader: ClassLoader): Boolean =
        runCatching { loader.loadClass(LAUNCHER_FACTORY) }.isSuccess &&
            runCatching { loader.loadClass(JUPITER_ENGINE) }.isSuccess

    fun run(
        loader: ClassLoader,
        testClassNames: List<String>,
        onOutcome: (TestOutcome) -> Unit,
    ) {
        val launcherFactory = loader.loadClass(LAUNCHER_FACTORY)
        val launcherConfig = loader.loadClass("org.junit.platform.launcher.core.LauncherConfig")
        val configBuilder = loader.loadClass("org.junit.platform.launcher.core.LauncherConfig\$Builder")
        val testEngine = loader.loadClass("org.junit.platform.engine.TestEngine")
        val requestBuilderClass = loader.loadClass("org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder")
        val discoverySelectors = loader.loadClass("org.junit.platform.engine.discovery.DiscoverySelectors")
        val requestClass = loader.loadClass("org.junit.platform.launcher.LauncherDiscoveryRequest")
        val launcherClass = loader.loadClass("org.junit.platform.launcher.Launcher")
        val listenerClass = loader.loadClass("org.junit.platform.launcher.TestExecutionListener")
        val identifierClass = loader.loadClass("org.junit.platform.launcher.TestIdentifier")
        val resultClass = loader.loadClass("org.junit.platform.engine.TestExecutionResult")
        val methodSource = loader.loadClass("org.junit.platform.engine.support.descriptor.MethodSource")
        val classSource = loader.loadClass("org.junit.platform.engine.support.descriptor.ClassSource")

        fun engine(name: String): Any? = runCatching {
            loader.loadClass(name).getDeclaredConstructor().newInstance()
        }.getOrNull()

        val jupiter = engine(JUPITER_ENGINE) ?: run {
            onOutcome(
                TestOutcome("<runner>", "JUnit 5", TestOutcomeStatus.ERROR, 0, "No se encontró el engine de JUnit 5 (Jupiter).")
            )
            return
        }
        val vintage = engine(VINTAGE_ENGINE)
        val suite = engine("org.junit.platform.suite.engine.SuiteTestEngine")
        val engineList = listOfNotNull(jupiter, vintage, suite)

        val builder = launcherConfig.getMethod("builder").invoke(null)
        for (flag in listOf(
            "enableTestEngineAutoRegistration",
            "enableLauncherSessionListenerAutoRegistration",
            "enableLauncherDiscoveryListenerAutoRegistration",
            "enableTestExecutionListenerAutoRegistration",
            "enablePostDiscoveryFilterAutoRegistration",
        )) {
            configBuilder.getMethod(flag, java.lang.Boolean.TYPE).invoke(builder, false)
        }
        val engines = ReflectArray.newInstance(testEngine, engineList.size)
        engineList.forEachIndexed { i, e -> ReflectArray.set(engines, i, e) }
        configBuilder.getMethod("addTestEngines", engines.javaClass).invoke(builder, engines)
        val config = configBuilder.getMethod("build").invoke(builder)
        val launcher = launcherFactory.getMethod("create", launcherConfig).invoke(null, config)

        val requestBuilder = requestBuilderClass.getMethod("request").invoke(null)
        val selectClass = discoverySelectors.getMethod("selectClass", Class::class.java)
        val selectors = ArrayList<Any>()
        for (name in testClassNames) {
            val cls = try {
                loader.loadClass(name)
            } catch (t: Throwable) {
                onOutcome(
                    TestOutcome(name, "<class>", TestOutcomeStatus.ERROR, 0, t.toString(), t.stackTraceToString())
                )
                continue
            }
            runCatching { selectClass.invoke(null, cls) }.getOrNull()?.let { selectors.add(it) }
        }
        if (selectors.isEmpty()) return
        requestBuilderClass.getMethod("selectors", java.util.List::class.java).invoke(requestBuilder, selectors)
        val request = requestBuilderClass.getMethod("build").invoke(requestBuilder)

        val startedAt = ConcurrentHashMap<String, Long>()
        val uid = { id: Any -> identifierClass.getMethod("getUniqueId").invoke(id) as String }
        val isTest = { id: Any -> identifierClass.getMethod("isTest").invoke(id) as Boolean }

        fun sourceOf(id: Any): Pair<String, String> {
            val display = identifierClass.getMethod("getDisplayName").invoke(id) as String
            val source = (identifierClass.getMethod("getSource").invoke(id) as Optional<*>).orElse(null)
            return when {
                source == null -> display to display
                methodSource.isInstance(source) -> {
                    val cls = methodSource.getMethod("getClassName").invoke(source) as String
                    val method = methodSource.getMethod("getMethodName").invoke(source) as String
                    val name = if (display.contains('[') && method in display) display else method
                    cls to name
                }
                classSource.isInstance(source) ->
                    (classSource.getMethod("getClassName").invoke(source) as String) to "<class>"
                else -> display to display
            }
        }

        fun emit(id: Any, status: TestOutcomeStatus, duration: Long, message: String = "", trace: String = "") {
            val (cls, name) = sourceOf(id)
            onOutcome(
                TestOutcome(
                    cls.ifEmpty { "<test>" }, name.ifEmpty { "<test>" }, status, duration, message, trace,
                )
            )
        }

        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "executionStarted" -> {
                    val id = args!![0]!!
                    startedAt[uid(id)] = System.currentTimeMillis()
                    null
                }
                "executionFinished" -> {
                    val id = args!![0]!!
                    val result = args[1]
                    val start = startedAt.remove(uid(id)) ?: System.currentTimeMillis()
                    val duration = (System.currentTimeMillis() - start).coerceAtLeast(0)
                    val status = resultClass.getMethod("getStatus").invoke(result).toString()
                    val throwable = (resultClass.getMethod("getThrowable").invoke(result) as Optional<*>).orElse(null)
                    val message = throwable?.let { (it as? Throwable)?.message ?: it.toString() }.orEmpty()
                    val trace = throwable?.let { (it as? Throwable)?.stackTraceToString() ?: "" }.orEmpty()
                    if (isTest(id)) {
                        val outcome = when (status) {
                            "FAILED" -> TestOutcomeStatus.FAILED
                            "ABORTED" -> TestOutcomeStatus.SKIPPED
                            else -> TestOutcomeStatus.PASSED
                        }
                        emit(id, outcome, duration, message, trace)
                    } else if (status == "FAILED") {
                        // Container failure (e.g. @BeforeAll threw): report once as a class error.
                        emit(id, TestOutcomeStatus.ERROR, duration, message, trace)
                    }
                    null
                }
                "executionSkipped" -> {
                    val id = args!![0]!!
                    val reason = args[1] as? String ?: ""
                    emit(id, TestOutcomeStatus.SKIPPED, 0, reason)
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                "toString" -> "CodeStudioJUnit5Listener"
                else -> null
            }
        }
        val listener = Proxy.newProxyInstance(loader, arrayOf(listenerClass), handler)
        val listeners = ReflectArray.newInstance(listenerClass, 1)
        ReflectArray.set(listeners, 0, listener)
        try {
            launcherClass.getMethod("execute", requestClass, listeners.javaClass).invoke(launcher, request, listeners)
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            onOutcome(
                TestOutcome("<runner>", "JUnit 5", TestOutcomeStatus.ERROR, 0, cause.toString(), cause.stackTraceToString())
            )
        }
    }
}
