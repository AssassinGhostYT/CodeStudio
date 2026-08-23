package dev.ide.agent.impl.opencode

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Safely manages isolated directory paths rooted strictly under `context.filesDir/opencode/`.
 */
class OpenCodePaths(val baseFilesDir: File) {

    val rootDir: File = File(baseFilesDir, "opencode")
    val runtimeDir: File = File(rootDir, "runtime")
    val agentsDir: File = File(rootDir, "agents")
    val stateProjectsDir: File = File(rootDir, "state/projects")
    val logsDir: File = File(rootDir, "logs")
    val scriptsDir: File = File(rootDir, "scripts")
    val registryDir: File = File(rootDir, "registry")
    val tmpDir: File = File(rootDir, "tmp")

    /**
     * Idempotently creates the directory tree under files/opencode/.
     */
    fun ensureDirectories() {
        val dirs = listOf(
            rootDir, runtimeDir, agentsDir, stateProjectsDir,
            logsDir, scriptsDir, registryDir, tmpDir
        )
        for (dir in dirs) {
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
    }

    /**
     * Resolves a safe sub-path under files/opencode/.
     * Rejects absolute paths, path traversal, escaping files/opencode, and references to support/storage.
     */
    fun resolveSubPath(subPath: String): File {
        require(subPath.isNotBlank()) { "Subpath must not be blank" }
        require(!subPath.startsWith("/")) { "Absolute subpaths are rejected: $subPath" }
        require(!subPath.contains("\u0000")) { "Null bytes are rejected in subpath" }

        val rootNormalized = rootDir.toPath().normalize().toAbsolutePath()
        val resolved = rootNormalized.resolve(subPath).normalize().toAbsolutePath()

        if (!resolved.startsWith(rootNormalized)) {
            throw IllegalArgumentException("Path traversal escaping files/opencode rejected: $subPath")
        }

        val resolvedString = resolved.toString()
        val baseString = baseFilesDir.toPath().normalize().toAbsolutePath().toString()
        if (resolvedString.startsWith("$baseString/support") || resolvedString.startsWith("$baseString/storage")) {
            throw IllegalArgumentException("Access to files/support or files/storage rejected: $subPath")
        }

        return resolved.toFile()
    }

    fun projectStateDir(projectId: String): File {
        require(projectId.isNotBlank()) { "projectId must not be blank" }
        return File(stateProjectsDir, projectId)
    }
}
