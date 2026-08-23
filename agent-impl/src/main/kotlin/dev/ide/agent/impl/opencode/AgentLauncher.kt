package dev.ide.agent.impl.opencode

import java.io.File

data class ProcessSpec(
    val executable: String,
    val commandArgs: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: String,
    val bindMounts: List<String>,
    val targetPort: Int
)

object AgentLauncher {

    fun buildInertProcessSpec(
        baseFilesDir: File,
        projectId: String,
        targetPort: Int,
        opencodeApiKey: String? = null
    ): ProcessSpec {
        val paths = OpenCodePaths(baseFilesDir)
        val projectState = paths.projectStateDir(projectId)
        val prootBin = File(paths.runtimeDir, "proot").absolutePath
        val rootfsDir = File(paths.runtimeDir, "rootfs").absolutePath
        val agentBin = File(paths.resolveSubPath("agents/opencode/1.18.18"), "opencode").absolutePath

        // A fixed, persisted port keeps the opencode web origin (host:port) stable across restarts;
        // a random port per launch resets the browser session and makes history look unsaved.
        // targetPort <= 0 opts into the persisted port.
        val effectivePort = when {
            targetPort > 0 -> targetPort
            else -> StablePortProvider.resolveStablePort(projectId, paths)
        }

        val envMutable = mutableMapOf(
            "OPENCODE_EXECUTABLE" to agentBin,
            "OPENCODE_PORT" to effectivePort.toString(),
            "OPENCODE_STATE_ROOT" to projectState.absolutePath,
            "HOME" to projectState.absolutePath,
            "XDG_CONFIG_HOME" to File(projectState, ".config").absolutePath,
            "XDG_DATA_HOME" to File(projectState, ".local/share").absolutePath,
            "XDG_CACHE_HOME" to File(projectState, ".cache").absolutePath,
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "BROWSER" to "true"
        )
        if (!opencodeApiKey.isNullOrBlank()) {
            envMutable["OPENCODE_API_KEY"] = opencodeApiKey
        }
        val env = envMutable.toMap()

        // Network bindings the sandbox needs to reach the LLM gateway (opencode.ai/zen/v1):
        // DNS (resolv.conf/hosts) and the TLS trust store. Bound only when present on the host so a
        // device without them keeps the previous behaviour instead of failing proot startup.
        val networkBinds = listOf("/etc/resolv.conf", "/etc/hosts", "/etc/ssl")
            .filter { File(it).exists() }

        val binds = listOf(
            "/sys", "/dev", "/proc", "/data", "/mnt"
        ) + networkBinds

        val args = mutableListOf(
            "-r", rootfsDir,
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/dev"
        )
        networkBinds.forEach { args.addAll(listOf("-b", it)) }
        args.addAll(listOf(agentBin, "web", "--port", effectivePort.toString(), "--hostname", "127.0.0.1"))

        return ProcessSpec(
            executable = prootBin,
            commandArgs = args,
            environment = env,
            workingDirectory = paths.tmpDir.absolutePath,
            bindMounts = binds,
            targetPort = effectivePort
        )
    }
}
