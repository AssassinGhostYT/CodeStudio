package dev.ide.agent.impl.opencode

import java.io.File

object RootFSManager {

    fun validateAndEnsureIsolatedDirectories(baseFilesDir: File): Map<String, File> {
        val rootDir = OpenCodePaths(baseFilesDir).rootDir
        val stateDir = File(rootDir, "state")
        val logsDir = File(rootDir, "logs")
        val stagingDir = File(rootDir, "tmp/staging")
        val workspaceDir = File(rootDir, "tmp/workspace")

        val dirs = mapOf(
            "root" to rootDir,
            "state" to stateDir,
            "logs" to logsDir,
            "staging" to stagingDir,
            "workspace" to workspaceDir
        )

        for ((_, dir) in dirs) {
            val canonical = dir.canonicalPath
            require(canonical.startsWith(rootDir.canonicalPath)) {
                "Directory escape detected for: $canonical"
            }
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }

        return dirs
    }
}
