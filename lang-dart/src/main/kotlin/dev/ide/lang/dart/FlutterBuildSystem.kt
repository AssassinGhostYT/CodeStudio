package dev.ide.lang.dart

import dev.ide.build.BuildContext
import dev.ide.build.BuildGoal
import dev.ide.build.BuildRequest
import dev.ide.build.BuildSystem
import dev.ide.build.RunAction
import dev.ide.build.RunTaskSpec
import dev.ide.build.Task
import dev.ide.build.TaskDescriptor
import dev.ide.build.TaskGraph
import dev.ide.build.TaskName
import dev.ide.build.TaskResult
import dev.ide.build.engine.DefaultTaskContainer
import dev.ide.build.engine.SimpleBuildConfiguration
import dev.ide.build.engine.applyBuildPlugins
import dev.ide.model.BuildSystemId
import dev.ide.model.Module
import dev.ide.model.ModuleType
import dev.ide.model.Project
import java.io.File
import java.nio.file.Path

/**
 * The Flutter/Dart build system: shells out to the Flutter CLI for build, run, and test tasks.
 * Supports flutter-app and dart-console module types.
 */
class FlutterBuildSystem : BuildSystem {

    override val id: BuildSystemId = BuildSystemId("flutter")

    override fun supports(moduleType: ModuleType): Boolean =
        moduleType.id == "flutter-app" || moduleType.id == "dart-console"

    override fun tasks(project: Project): List<TaskDescriptor> = listOf(
        TaskDescriptor("flutterRun", "run", "Run the Flutter app on connected device"),
        TaskDescriptor("flutterBuildApk", "build", "Build Android APK"),
        TaskDescriptor("flutterBuildIos", "build", "Build iOS app"),
        TaskDescriptor("flutterBuildAppbundle", "build", "Build Android App Bundle (.aab)"),
        TaskDescriptor("flutterTest", "test", "Run Flutter tests"),
        TaskDescriptor("flutterClean", "build", "Clean build artifacts"),
    )

    override fun runTasks(project: Project): List<RunTaskSpec> {
        val specs = mutableListOf<RunTaskSpec>()
        for (module in project.modules) {
            if (!supports(module.type)) continue

            if (module.type.id == "flutter-app") {
                // Run on connected device
                specs.add(RunTaskSpec(
                    id = "flutterRun:${module.name}",
                    label = "Run ${module.name}",
                    group = "flutter"
                ))
                // Build APK - debug
                specs.add(RunTaskSpec(
                    id = "flutterBuildApk:${module.name}:debug",
                    label = "Build APK (debug) · ${module.name}",
                    group = "flutter"
                ))
                // Build APK - release
                specs.add(RunTaskSpec(
                    id = "flutterBuildApk:${module.name}:release",
                    label = "Build APK (release) · ${module.name}",
                    group = "flutter"
                ))
                // Build App Bundle - release
                specs.add(RunTaskSpec(
                    id = "flutterBuildAppbundle:${module.name}:release",
                    label = "Build AAB (release) · ${module.name}",
                    group = "flutter"
                ))
                // Build iOS
                specs.add(RunTaskSpec(
                    id = "flutterBuildIos:${module.name}:release",
                    label = "Build iOS · ${module.name}",
                    group = "flutter"
                ))
            } else if (module.type.id == "dart-console") {
                // Run Dart console app
                specs.add(RunTaskSpec(
                    id = "flutterRun:${module.name}",
                    label = "Run ${module.name}",
                    group = "dart"
                ))
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
            "flutterRun" -> createFlutterRunAction(module, moduleDir, variant)
            "flutterBuildApk" -> createFlutterBuildAction(module, moduleDir, "apk", variant)
            "flutterBuildAppbundle" -> createFlutterBuildAction(module, moduleDir, "appbundle", variant)
            "flutterBuildIos" -> createFlutterBuildAction(module, moduleDir, "ios", variant)
            else -> null
        }
    }

    override fun createBuildGraph(project: Project, request: BuildRequest): TaskGraph {
        val tasks = DefaultTaskContainer()
        val config = SimpleBuildConfiguration(project, request, tasks, id, null)
        applyBuildPlugins(config, emptyList())
        return tasks.build()
    }

    private fun createFlutterRunAction(
        module: Module,
        moduleDir: File,
        variant: String
    ): RunAction {
        val args = mutableListOf("run")
        if (variant == "release") args.add("--release")
        else if (variant == "profile") args.add("--profile")
        else args.add("--debug")

        return RunAction(
            header = "Flutter Run · ${module.name} ($variant)",
            graph = createSingleTaskGraph(
                TaskName("flutter:${module.name}:run"),
                moduleDir.toPath(),
                "flutter",
                args
            ),
            onSuccess = { log ->
                log("App started on connected device")
            }
        )
    }

    private fun createFlutterBuildAction(
        module: Module,
        moduleDir: File,
        buildType: String,
        variant: String
    ): RunAction {
        val args = mutableListOf("build", buildType)
        if (variant == "release") args.add("--release")
        else if (variant == "profile") args.add("--profile")
        else args.add("--debug")

        val outputDir = when (buildType) {
            "apk" -> moduleDir.resolve("build/app/outputs/flutter-apk")
            "appbundle" -> moduleDir.resolve("build/app/outputs/bundle/release")
            "ios" -> moduleDir.resolve("build/ios/iphoneos")
            else -> moduleDir.resolve("build")
        }

        return RunAction(
            header = "Flutter Build $buildType · ${module.name} ($variant)",
            graph = createSingleTaskGraph(
                TaskName("flutter:${module.name}:build-$buildType"),
                moduleDir.toPath(),
                "flutter",
                args
            ),
            banner = "Output: ${outputDir.absolutePath}",
            onSuccess = { log ->
                log("Build succeeded: ${outputDir.absolutePath}")
            }
        )
    }

    private fun createSingleTaskGraph(
        taskName: TaskName,
        workingDir: Path,
        command: String,
        args: List<String>
    ): TaskGraph {
        val tasks = DefaultTaskContainer()
        tasks.register(object : Task {
            override val name: TaskName = taskName
            override val inputs = dev.ide.build.engine.TaskInputsImpl().apply {
                property("command", command)
                property("args", args.joinToString(" "))
            }
            override val outputs = dev.ide.build.engine.TaskOutputsImpl()

            override suspend fun execute(ctx: dev.ide.build.TaskContext): TaskResult {
                val log = ctx.logger()
                log("Running: $command ${args.joinToString(" ")}")
                log("Working directory: $workingDir")

                return try {
                    val processBuilder = ProcessBuilder(command + args)
                        .directory(workingDir.toFile())
                        .redirectErrorStream(true)

                    val process = processBuilder.start()
                    val reader = process.inputStream.bufferedReader()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        log(line!!)
                    }

                    val exitCode = process.waitFor()
                    if (exitCode == 0) {
                        log("Command completed successfully")
                        TaskResult.Success
                    } else {
                        TaskResult.Failed("Command failed with exit code $exitCode")
                    }
                } catch (e: Exception) {
                    TaskResult.Failed("Failed to execute command: ${e.message}", e)
                }
            }
        })
        return tasks.build()
    }
}
