package dev.ide.core.services

import dev.ide.core.EngineContext
import dev.ide.lang.dart.FlutterHostRunner
import dev.ide.lang.dart.FlutterSdkManager
import dev.ide.model.Module
import dev.ide.ui.backend.UiTestResult
import dev.ide.ui.backend.UiTestStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Runs a Dart/Flutter module's tests for the Tests console tab.
 *
 * The two runners speak different JSON dialects (`dart test --reporter json` and `flutter test --machine`),
 * but both emit the same event names (`suite`/`testStart`/`error`/`testDone`), so one parser covers both.
 * Desktop executes the toolchain directly; Android routes Flutter through [FlutterHostRunner]'s Ubuntu/proot
 * runtime, because the SDK is a glibc binary. Pure Dart on Android has no such bridge (the bundled Dart SDK
 * is Linux too), so it reports that honestly instead of failing with an opaque exec error.
 */
internal class DartTestRunner(private val ctx: EngineContext) {

    private val sdk = FlutterSdkManager()

    /**
     * Runs the module's tests and streams each completed test through [onResult]. Returns an error message
     * when the run could not start or produced no parseable results, or `""` otherwise (including a normal
     * non-zero exit caused by failing tests — those are in the results).
     */
    fun run(
        module: Module,
        onlySuites: Set<String>,
        onResult: (UiTestResult) -> Unit,
        phase: (String) -> Unit,
    ): String {
        val moduleRoot = ctx.moduleRoot(module) ?: ctx.workspaceRoot
        if (!Files.isRegularFile(moduleRoot.resolve("pubspec.yaml"))) {
            return "No hay pubspec.yaml en ${module.name}."
        }
        val isFlutter = module.type.id == "flutter-app"
        val host = FlutterHostRunner.runner
        val onAndroid = host != null
        val tool = when {
            onAndroid && isFlutter -> null // the host supplies the SDK/proot command
            onAndroid -> return missingTool(isFlutter = false, onAndroid = true)
            else -> resolveTool(isFlutter, phase) ?: return missingTool(isFlutter, onAndroid = false)
        }

        // `pub get` is best-effort: the package config may already exist, and a network failure should not
        // hide the test results. The test command itself reports a missing dependency clearly.
        phase("Resolviendo dependencias de ${module.name}…")
        val pubArgs = listOf("pub", "get")
        if (isFlutter && host != null) {
            runBlocking { host(ctx.workspaceRoot, moduleRoot.toFile(), true, pubArgs) { } }
        } else {
            runProcess(tool!!, moduleRoot, pubArgs) { }
        }

        // Rerun-failed: the suite is the test file, so re-running just those files is the exact
        // Dart/Flutter equivalent of Gradle's `--tests <class>`.
        val failedFiles = onlySuites.filter { Files.isRegularFile(moduleRoot.resolve(it)) }
        if (onlySuites.isNotEmpty() && failedFiles.isEmpty()) {
            return "No se encontraron los archivos de test de la corrida anterior."
        }
        val testArgs = buildList {
            add("test")
            if (isFlutter) {
                add("--machine")
            } else {
                add("--reporter")
                add("json")
            }
            addAll(failedFiles)
        }

        phase("Ejecutando tests de ${module.name}…")
        val parser = DartTestParser(moduleRoot, module.name, onResult)
        val tail = ArrayDeque<String>()
        val onLine: (String) -> Unit = { line ->
            tail.addLast(line)
            if (tail.size > 60) tail.removeFirst()
            parser.accept(line)
        }
        val code = if (isFlutter && host != null) {
            runBlocking { host(ctx.workspaceRoot, moduleRoot.toFile(), true, testArgs, onLine) }
        } else {
            runProcess(tool!!, moduleRoot, testArgs, onLine)
        }
        parser.finish()
        if (code != 0 && parser.count == 0) {
            val detail = tail.takeLast(20).joinToString("\n").trim()
            return "No se pudieron ejecutar los tests de ${module.name} (código $code)." +
                if (detail.isEmpty()) "" else "\n$detail"
        }
        return ""
    }

    /** Resolves the `dart`/`flutter` executable; on desktop Dart is downloaded once if it is missing. */
    private fun resolveTool(isFlutter: Boolean, phase: (String) -> Unit): String? {
        if (!isFlutter) {
            val installed = sdk.dartBin(ctx.workspaceRoot)
            if (Files.isExecutable(installed)) return installed.toString()
            phase("Descargando el Dart SDK (primera vez)…")
            val err = sdk.ensureDownloaded(ctx.workspaceRoot) { read, total ->
                val mb = if (total > 0) " de %.1f MB".format(total / 1_048_576.0) else ""
                phase("Descargando el Dart SDK… " + "%.1f MB".format(read / 1_048_576.0) + mb)
            }
            if (err.isNotEmpty()) return null
            val downloaded = sdk.dartBin(ctx.workspaceRoot)
            return downloaded.takeIf { Files.isExecutable(it) }?.toString()
        }
        val candidates = ArrayList<File>()
        candidates.add(sdk.flutterBin(ctx.workspaceRoot).toFile())
        System.getenv("FLUTTER_BIN")?.takeIf { it.isNotBlank() }?.let {
            val configured = File(it)
            candidates.add(if (configured.isDirectory) File(configured, "flutter") else configured)
        }
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { candidates.add(File(it, "flutter")) }
        candidates.add(File(System.getProperty("user.home"), "flutter/bin/flutter"))
        return candidates.firstOrNull { it.canExecute() }?.absolutePath
    }

    private fun missingTool(isFlutter: Boolean, onAndroid: Boolean): String = when {
        isFlutter -> "Flutter SDK no encontrado. Instálalo o define FLUTTER_BIN a su directorio bin."
        onAndroid ->
            "Los tests de Dart puro en Android necesitan el runtime Ubuntu/Flutter. " +
                "Ejecútalos desde escritorio o usa un proyecto Flutter."
        else -> "Dart SDK no disponible y no se pudo descargar. Revisa la conexión e inténtalo de nuevo."
    }

    private fun runProcess(tool: String, workDir: Path, args: List<String>, onLine: (String) -> Unit): Int {
        return try {
            val process = ProcessBuilder(listOf(tool) + args)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start()
            val reader = Thread {
                runCatching { process.inputStream.bufferedReader().use { r -> r.forEachLine(onLine) } }
            }.apply {
                isDaemon = true
                start()
            }
            val finished = process.waitFor(30, TimeUnit.MINUTES)
            if (!finished) process.destroyForcibly()
            reader.join(5_000)
            if (finished) process.exitValue() else -1
        } catch (e: Exception) {
            onLine("No se pudo ejecutar $tool: ${e.message}")
            -1
        }
    }

    /** Parses the shared `suite`/`testStart`/`error`/`testDone` event stream of both Dart test reporters. */
    private class DartTestParser(
        private val moduleRoot: Path,
        private val moduleName: String,
        private val onResult: (UiTestResult) -> Unit,
    ) {
        var count = 0
            private set

        private data class TestInfo(
            val name: String,
            val suiteId: Int,
            val line: Int,
            val url: String?,
            val skipped: Boolean,
            val startTime: Long,
        )

        private data class ErrorInfo(val message: String, val stack: String)

        private val suites = HashMap<Int, String>()
        private val tests = HashMap<Int, TestInfo>()
        private val errors = HashMap<Int, ErrorInfo>()

        fun accept(raw: String) {
            val line = raw.trim()
            if (!line.startsWith("{")) return
            val obj = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "suite" -> {
                    val suite = obj["suite"]?.jsonObject ?: return
                    val id = suite["id"]?.jsonPrimitive?.intOrNull ?: return
                    val path = suite["path"]?.jsonPrimitive?.contentOrNull ?: return
                    suites[id] = path
                }
                "testStart" -> {
                    val test = obj["test"]?.jsonObject ?: return
                    val id = test["id"]?.jsonPrimitive?.intOrNull ?: return
                    val name = test["name"]?.jsonPrimitive?.contentOrNull ?: return
                    tests[id] = TestInfo(
                        name = name,
                        suiteId = test["suiteID"]?.jsonPrimitive?.intOrNull ?: -1,
                        line = test["line"]?.jsonPrimitive?.intOrNull ?: -1,
                        url = test["url"]?.jsonPrimitive?.contentOrNull,
                        skipped = test["metadata"]?.jsonObject?.get("skip")?.jsonPrimitive?.booleanOrNull == true,
                        startTime = obj["time"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0,
                    )
                }
                "error" -> {
                    val id = obj["testID"]?.jsonPrimitive?.intOrNull ?: return
                    errors[id] = ErrorInfo(
                        message = obj["error"]?.jsonPrimitive?.contentOrNull ?: "",
                        stack = obj["stackTrace"]?.jsonPrimitive?.contentOrNull ?: "",
                    )
                }
                "testDone" -> {
                    val id = obj["testID"]?.jsonPrimitive?.intOrNull ?: return
                    val info = tests.remove(id) ?: return
                    if (obj["hidden"]?.jsonPrimitive?.booleanOrNull == true) return
                    val result = obj["result"]?.jsonPrimitive?.contentOrNull ?: "error"
                    val skipped = info.skipped || obj["skipped"]?.jsonPrimitive?.booleanOrNull == true ||
                        result == "skipped"
                    val status = when {
                        skipped -> UiTestStatus.Skipped
                        result == "success" -> UiTestStatus.Passed
                        result == "failure" -> UiTestStatus.Failed
                        else -> UiTestStatus.Error
                    }
                    val suitePath = (suites[info.suiteId] ?: info.url?.let { relative(it) })
                        ?.takeIf { it.isNotBlank() } ?: moduleName
                    val absolute = if (suitePath.startsWith("/")) suitePath else moduleRoot.resolve(suitePath).toString()
                    val endTime = obj["time"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: info.startTime
                    val error = errors.remove(id)
                    count++
                    onResult(
                        UiTestResult(
                            suite = suitePath,
                            name = info.name,
                            status = status,
                            durationMs = (endTime - info.startTime).coerceAtLeast(0),
                            message = error?.message ?: "",
                            stackTrace = error?.stack ?: "",
                            file = absolute,
                            line = info.line,
                        )
                    )
                }
            }
        }

        /** A test that started but never reported `testDone` means the runner died; surface it as an error. */
        fun finish() {
            tests.values.forEach { info ->
                if (info.name.startsWith("loading ")) return@forEach
                count++
                onResult(
                    UiTestResult(
                        suite = suites[info.suiteId] ?: moduleName,
                        name = info.name,
                        status = UiTestStatus.Error,
                        message = "El test no terminó: el runner de tests se detuvo.",
                        file = info.url?.let { relative(it) },
                        line = info.line,
                    )
                )
            }
            tests.clear()
        }

        private fun relative(url: String): String {
            val path = url.removePrefix("file://")
            return runCatching { moduleRoot.relativize(Path.of(path)).toString() }.getOrDefault(path)
        }
    }
}
