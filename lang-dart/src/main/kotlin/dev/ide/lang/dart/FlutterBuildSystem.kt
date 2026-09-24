package dev.ide.lang.dart

import dev.ide.build.BuildContext
import dev.ide.build.BuildGoal
import dev.ide.build.BuildRequest
import dev.ide.build.BuildSystem
import dev.ide.model.ClasspathSnapshot
import dev.ide.platform.ContentHash
import dev.ide.build.RunAction
import dev.ide.build.RunTaskSpec
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskDescriptor
import dev.ide.build.TaskGraph
import dev.ide.build.TaskInputs
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskResult
import dev.ide.model.BuildSystemId
import dev.ide.model.Module
import dev.ide.model.ModuleType
import dev.ide.model.Project
import dev.ide.vfs.VirtualFile
import java.io.File
import java.nio.file.Path

class FlutterBuildSystem(
    private val sdkManager: FlutterSdkManager = FlutterSdkManager(),
) : BuildSystem {

    override val id: BuildSystemId = BuildSystemId("flutter")

    override fun supports(moduleType: ModuleType): Boolean =
        moduleType.id == "flutter-app" || moduleType.id == "dart-console"

    override fun tasks(project: Project): List<TaskDescriptor> = listOf(
        TaskDescriptor("flutterRun", "run", "Run the Flutter app"),
        TaskDescriptor("flutterBuildApk", "build", "Build Android APK"),
        TaskDescriptor("flutterBuildAppbundle", "build", "Build Android App Bundle"),
        TaskDescriptor("dartPubGet", "dependencies", "Get Dart/Flutter dependencies"),
        TaskDescriptor("dartAnalyze", "verify", "Analyze Dart code"),
        TaskDescriptor("dartTest", "verify", "Run Dart tests"),
        TaskDescriptor("dartCompile", "build", "Compile Dart executable"),
    )

    override fun runTasks(project: Project): List<RunTaskSpec> {
        val specs = mutableListOf<RunTaskSpec>()
        for (module in project.modules) {
            if (!supports(module.type)) continue
            if (module.type.id == "flutter-app") {
                specs.add(RunTaskSpec("flutterRun:${module.name}", "Run ${module.name}", "flutter"))
                specs.add(RunTaskSpec("flutterBuildApk:${module.name}:debug", "Build APK (debug) · ${module.name}", "flutter"))
                specs.add(RunTaskSpec("flutterBuildApk:${module.name}:release", "Build APK (release) · ${module.name}", "flutter"))
                specs.add(RunTaskSpec("flutterBuildAppbundle:${module.name}:debug", "Build AAB (debug) · ${module.name}", "flutter"))
                specs.add(RunTaskSpec("flutterBuildAppbundle:${module.name}:release", "Build AAB (release) · ${module.name}", "flutter"))
            } else if (module.type.id == "dart-console") {
                specs.add(RunTaskSpec("dartPubGet:${module.name}", "Get dependencies · ${module.name}", "dart"))
                specs.add(RunTaskSpec("flutterRun:${module.name}", "Run ${module.name}", "dart"))
                specs.add(RunTaskSpec("dartAnalyze:${module.name}", "Analyze · ${module.name}", "dart"))
                specs.add(RunTaskSpec("dartTest:${module.name}", "Test · ${module.name}", "dart"))
                specs.add(RunTaskSpec("dartCompile:${module.name}", "Compile executable · ${module.name}", "dart"))
            }
        }
        return specs
    }

    override fun actionFor(spec: RunTaskSpec, project: Project, ctx: BuildContext): RunAction? {
        val parts = spec.id.split(":")
        if (parts.size < 2) return null
        val command = parts[0]
        val moduleName = parts[1]
        val variant = parts.getOrNull(2) ?: "debug"
        val module = project.modules.find { it.name == moduleName } ?: return null
        val moduleDir = File(project.rootDir.path).resolve(moduleName)
        val workspacePath = java.nio.file.Paths.get(project.rootDir.path)

        return when (command) {
            "flutterRun" -> runAction(module, moduleDir, variant, workspacePath)
            "flutterBuildApk" -> buildAction(module, moduleDir, "apk", variant, workspacePath)
            "flutterBuildAppbundle" -> buildAction(module, moduleDir, "appbundle", variant, workspacePath)
            "dartPubGet" -> dartAction(module, moduleDir, listOf("pub", "get"), workspacePath, "Dart Dependencies")
            "dartAnalyze" -> dartAction(module, moduleDir, listOf("analyze"), workspacePath, "Dart Analyze")
            "dartTest" -> dartAction(module, moduleDir, listOf("test"), workspacePath, "Dart Test")
            "dartCompile" -> dartAction(module, moduleDir, listOf("compile", "exe", "bin/${module.name}.dart"), workspacePath, "Dart Compile")
            else -> null
        }
    }

    override fun createBuildGraph(project: Project, request: BuildRequest): TaskGraph {
        // Build the requested target modules through the Flutter/Dart CLI. Flutter modules produce Android
        // artifacts; Dart console modules are verified/compiled with the Dart SDK and never masquerade as APKs.
        val variant = request.variant.name.ifBlank { "debug" }
        val workspacePath = java.nio.file.Paths.get(project.rootDir.path)
        val targets = request.targets.ifEmpty { project.modules.filter { supports(it.type) }.map { it.id } }
        val graphs = mutableListOf<TaskGraph>()
        for (moduleId in targets) {
            val module = project.modules.find { it.id == moduleId } ?: continue
            if (!supports(module.type)) continue
            val dir = File(project.rootDir.path).resolve(module.name)
            if (module.type.id == "dart-console") {
                val args = when (request.goal) {
                    BuildGoal.TEST -> listOf("test")
                    BuildGoal.LINT, BuildGoal.COMPILE_ONLY -> listOf("analyze")
                    else -> listOf("compile", "exe", "bin/${module.name}.dart")
                }
                graphs += cliTaskGraph(
                    TaskName("dart:${module.name}:${args.first()}"),
                    dir,
                    module,
                    workspacePath,
                    args,
                )
            } else {
                val buildType = if (request.goal == BuildGoal.BUNDLE) "appbundle" else "apk"
                val args = listOf("build", buildType, "--$variant")
                graphs += cliTaskGraph(
                    TaskName("flutter:${module.name}:build-$buildType"),
                    dir,
                    module,
                    workspacePath,
                    args,
                )
            }
        }
        if (graphs.isEmpty()) {
            throw IllegalArgumentException("No Flutter/Dart modules to build in '${project.name}'.")
        }
        val tasks = graphs.flatMap { it.tasks }
        return object : TaskGraph {
            override val tasks: List<Task> = tasks
            override fun dependencies(t: Task): List<Task> =
                graphs.firstOrNull { t in it.tasks }?.dependencies(t).orEmpty()
            override fun topologicalLevels(): List<List<Task>> = buildList {
                val maxLevels = graphs.maxOfOrNull { it.topologicalLevels().size } ?: 0
                repeat(maxLevels) { level ->
                    add(graphs.flatMap { it.topologicalLevels().getOrNull(level).orEmpty() })
                }
            }
        }
    }

    private fun runAction(module: Module, dir: File, variant: String, workspacePath: Path): RunAction {
        val isDartConsole = module.type.id == "dart-console"
        // `dart run` has no build-variant flag; only the Flutter runner takes `--$variant`.
        val args = if (isDartConsole) mutableListOf("run") else mutableListOf("run", "--$variant")
        val tool = if (isDartConsole) "dart" else "flutter"
        return RunAction(
            header = if (isDartConsole) "Dart Run · ${module.name}" else "Flutter Run · ${module.name} ($variant)",
            graph = cliTaskGraph(TaskName("$tool:${module.name}:run"), dir, module, workspacePath, args),
            onSuccess = { log -> log("App started") }
        )
    }

    private fun buildAction(module: Module, dir: File, buildType: String, variant: String, workspacePath: Path): RunAction {
        val args = mutableListOf("build", buildType, "--$variant")
        val output = when (buildType) {
            "apk" -> dir.resolve("build/app/outputs/flutter-apk")
            "appbundle" -> dir.resolve("build/app/outputs/bundle/$variant")
            else -> dir.resolve("build")
        }
        return RunAction(
            header = "Flutter Build $buildType · ${module.name} ($variant)",
            graph = cliTaskGraph(TaskName("flutter:${module.name}:build-$buildType"), dir, module, workspacePath, args),
            banner = "Output: ${output.absolutePath}",
            onSuccess = { log -> log("Build succeeded: ${output.absolutePath}") }
        )
    }

    private fun dartAction(
        module: Module,
        dir: File,
        args: List<String>,
        workspacePath: Path,
        title: String,
    ): RunAction = RunAction(
        header = "$title · ${module.name}",
        graph = cliTaskGraph(TaskName("dart:${module.name}:${args.first()}"), dir, module, workspacePath, args),
        onSuccess = { log -> log("Completed successfully") },
    )

    private fun cliTask(
        name: TaskName,
        workingDir: File,
        module: Module,
        workspacePath: Path,
        args: List<String>,
    ): Task = object : Task {
        override val name: TaskName = name
        override val inputs: TaskInputs = object : TaskInputs {
            override fun files(key: String, files: Iterable<VirtualFile>) {}
            override fun property(key: String, value: Any?) {}
            override fun classpath(key: String, cp: ClasspathSnapshot) {}
            override fun fingerprint(): ContentHash = ContentHash("")
        }
        override val outputs: TaskOutputs = object : TaskOutputs {
            override fun files(key: String, files: Iterable<VirtualFile>) {}
            override fun dir(key: String, dir: VirtualFile) {}
            override fun fingerprint(): ContentHash = ContentHash("")
        }
        override suspend fun execute(ctx: TaskContext): TaskResult {
            val log = ctx.logger()
            val tool = resolveTool(module, workspacePath, log) ?: return TaskResult.Failed(
                if (module.type.id == "dart-console") dartMissingMessage() else flutterMissingMessage()
            )

            log("Running: $tool ${args.joinToString(" ")}")
            return runProcess(tool, workingDir, args, log)
        }
    }

    private fun resolveTool(module: Module, workspacePath: Path, log: (String) -> Unit): File? {
        if (module.type.id == "dart-console") {
            val dart = sdkManager.dartBin(workspacePath).toFile()
            if (!sdkManager.isInstalled(workspacePath)) {
                log("Dart SDK not installed yet — downloading it once…")
                val err = sdkManager.ensureDownloaded(workspacePath) { read, total ->
                    val mb = if (total > 0) " of %.1f MB".format(total / 1_048_576.0) else ""
                    log("  %.1f MB$mb".format(read / 1_048_576.0))
                }
                if (err.isNotEmpty()) {
                    log(err)
                    return null
                }
            }
            return dart.takeIf { it.canExecute() }
        }
        return sdkManager.flutterBin(workspacePath).toFile().takeIf { it.canExecute() } ?: findFlutter()
    }

    private fun runProcess(tool: File, workingDir: File, args: List<String>, log: (String) -> Unit): TaskResult {
        return try {
            val pb = ProcessBuilder(listOf(tool.absolutePath) + args)
                .directory(workingDir)
                .redirectErrorStream(true)
            val proc = pb.start()
            proc.inputStream.bufferedReader().forEachLine { log(it) }
            if (proc.waitFor() == 0) TaskResult.Success
            else TaskResult.Failed("Exit code ${proc.exitValue()}")
        } catch (e: Exception) {
            TaskResult.Failed("Failed: ${e.message}", e)
        }
    }

    /** Locate the `flutter` binary: the FLUTTER_BIN env var, then the PATH, then common Android/Termux paths. */
    private fun findFlutter(): File? {
        System.getenv("FLUTTER_BIN")?.let {
            val f = File(it)
            if (f.canExecute()) return f
        }
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach {
            val f = File(it, "flutter")
            if (f.canExecute()) return f
        }
        val common = listOf(
            "/data/data/com.termux/files/usr/bin/flutter",
            "/data/data/com.termux/files/usr/local/bin/flutter",
            System.getProperty("user.home") + "/flutter/bin/flutter",
            System.getProperty("user.home") + "/development/flutter/bin/flutter",
            "/data/data/com.termux/files/home/flutter/bin/flutter",
        )
        for (p in common) {
            val f = File(p)
            if (f.canExecute()) return f
        }
        return null
    }

    private fun flutterMissingMessage(): String =
        "Flutter SDK not found. Install Flutter through the terminal or set FLUTTER_BIN to its bin directory. " +
            "CodeStudio will then run flutter pub get and cache the project dependencies before building."

    private fun dartMissingMessage(): String =
        "Dart SDK could not be installed or started. Check the network connection and try Dart Dependencies again."

    private fun cliTaskGraph(
        name: TaskName,
        workingDir: File,
        module: Module,
        workspacePath: Path,
        args: List<String>,
    ): TaskGraph {
        if (args == listOf("pub", "get")) {
            val task = cliTask(name, workingDir, module, workspacePath, args)
            return object : TaskGraph {
                override val tasks: List<Task> = listOf(task)
                override fun dependencies(t: Task): List<Task> = emptyList()
                override fun topologicalLevels(): List<List<Task>> = listOf(listOf(task))
            }
        }
        val prepare = cliTask(
            TaskName("${name.value}:pub-get"),
            workingDir,
            module,
            workspacePath,
            listOf("pub", "get"),
        )
        val task = cliTask(name, workingDir, module, workspacePath, args)
        return object : TaskGraph {
            override val tasks: List<Task> = listOf(prepare, task)
            override fun dependencies(t: Task): List<Task> = if (t === task) listOf(prepare) else emptyList()
            override fun topologicalLevels(): List<List<Task>> = listOf(listOf(prepare), listOf(task))
        }
    }
}
