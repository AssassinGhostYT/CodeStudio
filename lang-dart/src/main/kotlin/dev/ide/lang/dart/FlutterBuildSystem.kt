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

class FlutterBuildSystem : BuildSystem {

    override val id: BuildSystemId = BuildSystemId("flutter")

    override fun supports(moduleType: ModuleType): Boolean =
        moduleType.id == "flutter-app" || moduleType.id == "dart-console"

    override fun tasks(project: Project): List<TaskDescriptor> = listOf(
        TaskDescriptor("flutterRun", "run", "Run the Flutter app"),
        TaskDescriptor("flutterBuildApk", "build", "Build Android APK"),
        TaskDescriptor("flutterBuildIos", "build", "Build iOS app"),
        TaskDescriptor("flutterBuildAppbundle", "build", "Build Android App Bundle"),
    )

    override fun runTasks(project: Project): List<RunTaskSpec> {
        val specs = mutableListOf<RunTaskSpec>()
        for (module in project.modules) {
            if (!supports(module.type)) continue
            if (module.type.id == "flutter-app") {
                specs.add(RunTaskSpec("flutterRun:${module.name}", "Run ${module.name}", "flutter"))
                specs.add(RunTaskSpec("flutterBuildApk:${module.name}:debug", "Build APK (debug) · ${module.name}", "flutter"))
                specs.add(RunTaskSpec("flutterBuildApk:${module.name}:release", "Build APK (release) · ${module.name}", "flutter"))
                specs.add(RunTaskSpec("flutterBuildAppbundle:${module.name}:release", "Build AAB (release) · ${module.name}", "flutter"))
                specs.add(RunTaskSpec("flutterBuildIos:${module.name}:release", "Build iOS · ${module.name}", "flutter"))
            } else if (module.type.id == "dart-console") {
                specs.add(RunTaskSpec("flutterRun:${module.name}", "Run ${module.name}", "dart"))
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

        return when (command) {
            "flutterRun" -> runAction(module, moduleDir, variant)
            "flutterBuildApk" -> buildAction(module, moduleDir, "apk", variant)
            "flutterBuildAppbundle" -> buildAction(module, moduleDir, "appbundle", variant)
            "flutterBuildIos" -> buildAction(module, moduleDir, "ios", variant)
            else -> null
        }
    }

    override fun createBuildGraph(project: Project, request: BuildRequest): TaskGraph {
        // Build the requested target modules through the Flutter CLI. Each module fans out to its own
        // `flutter build` invocation; the graph runs them via the same engine the host uses for every task.
        val variant = request.variant.name.ifBlank { "debug" }
        val goalName = when (request.goal) {
            BuildGoal.BUNDLE -> "appbundle"
            BuildGoal.INSTALL -> "ios"
            else -> "apk"
        }
        val targets = request.targets.ifEmpty { project.modules.filter { supports(it.type) }.map { it.id } }
        val tasks = mutableListOf<Task>()
        for (moduleId in targets) {
            val module = project.modules.find { it.id == moduleId } ?: continue
            if (!supports(module.type)) continue
            val dir = File(project.rootDir.path).resolve(module.name)
            val args = listOf("build", goalName, "--$variant")
            tasks += cliTask(TaskName("flutter:${module.name}:build-$goalName"), dir, args)
        }
        if (tasks.isEmpty()) {
            throw IllegalArgumentException("No Flutter/Dart modules to build in '${project.name}'.")
        }
        return object : TaskGraph {
            override val tasks: List<Task> = tasks
            override fun dependencies(t: Task): List<Task> = emptyList()
            override fun topologicalLevels(): List<List<Task>> = listOf(tasks)
        }
    }

    private fun runAction(module: Module, dir: File, variant: String): RunAction {
        val args = mutableListOf("run", "--$variant")
        return RunAction(
            header = "Flutter Run · ${module.name} ($variant)",
            graph = cliTaskGraph(TaskName("flutter:${module.name}:run"), dir, args),
            onSuccess = { log -> log("App started") }
        )
    }

    private fun buildAction(module: Module, dir: File, buildType: String, variant: String): RunAction {
        val args = mutableListOf("build", buildType, "--$variant")
        val output = when (buildType) {
            "apk" -> dir.resolve("build/app/outputs/flutter-apk")
            "appbundle" -> dir.resolve("build/app/outputs/bundle/release")
            "ios" -> dir.resolve("build/ios/iphoneos")
            else -> dir.resolve("build")
        }
        return RunAction(
            header = "Flutter Build $buildType · ${module.name} ($variant)",
            graph = cliTaskGraph(TaskName("flutter:${module.name}:build-$buildType"), dir, args),
            banner = "Output: ${output.absolutePath}",
            onSuccess = { log -> log("Build succeeded: ${output.absolutePath}") }
        )
    }

    private fun cliTask(name: TaskName, workingDir: File, args: List<String>): Task = object : Task {
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
            val flutter = findFlutter()
            if (flutter == null) {
                log("Flutter SDK not found on this device.")
                log("The 'flutter' executable is required to run or build a Flutter app.")
                log("Install Flutter (e.g. via Termux) and make sure 'flutter' is on the PATH,")
                log("or set the FLUTTER_BIN environment variable to the full path of the flutter binary.")
                return TaskResult.Failed(
                    "Flutter SDK not found. Install Flutter and add it to PATH, or set FLUTTER_BIN to the flutter binary path."
                )
            }
            log("Running: $flutter ${args.joinToString(" ")}")
            return try {
                val pb = ProcessBuilder(listOf(flutter.absolutePath) + args)
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

    private fun cliTaskGraph(name: TaskName, workingDir: File, args: List<String>): TaskGraph {
        val task = cliTask(name, workingDir, args)
        return object : TaskGraph {
            override val tasks: List<Task> = listOf(task)
            override fun dependencies(t: Task): List<Task> = emptyList()
            override fun topologicalLevels(): List<List<Task>> = listOf(listOf(task))
        }
    }
}
