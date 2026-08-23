package dev.ide.agent.impl.opencode

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

data class ProjectValidationResult(
    val originalPath: String?,
    val normalizedPath: String?,
    val canonicalPath: String?,
    val isDirectory: Boolean,
    val canRead: Boolean,
    val canWrite: Boolean,
    val canExecute: Boolean,
    val isValid: Boolean,
    val reason: String
)

/**
 * Validates project candidate paths against security boundaries and filesystem traits.
 */
class ProjectRouteValidator(private val baseFilesDir: File? = null) {

    fun validate(rawPath: String?): ProjectValidationResult {
        if (rawPath.isNull_or_blank()) {
            return resultFail(rawPath, null, null, "Path is null or blank")
        }
        if (rawPath!!.contains("\u0000")) {
            return resultFail(rawPath, null, null, "Path contains null bytes")
        }

        val normalized: Path = runCatching { Paths.get(rawPath).normalize() }.getOrElse {
            return resultFail(rawPath, null, null, "Invalid path format: ${it.message}")
        }

        val normFile = normalized.toFile()
        if (!normFile.exists()) {
            return resultFail(rawPath, normalized.toString(), null, "Path does not exist on filesystem")
        }

        val isDir = normFile.isDirectory
        if (!isDir) {
            return resultFail(rawPath, normalized.toString(), null, "Path is a file, not a directory")
        }

        val canonicalPath = runCatching { normFile.canonicalPath }.getOrDefault(normFile.absolutePath)
        val canRead = normFile.canRead()
        val canWrite = normFile.canWrite()
        val canExecute = normFile.canExecute()

        if (!canRead) {
            return ProjectValidationResult(
                originalPath = rawPath,
                normalizedPath = normalized.toString(),
                canonicalPath = canonicalPath,
                isDirectory = true,
                canRead = false,
                canWrite = canWrite,
                canExecute = canExecute,
                isValid = false,
                reason = "Directory is not readable"
            )
        }

        if (baseFilesDir != null) {
            val baseAbs = baseFilesDir.toPath().normalize().toAbsolutePath().toString()
            val opencodeBase = "$baseAbs/opencode"
            val supportBase = "$baseAbs/support"
            val storageBase = "$baseAbs/storage"

            val canPath = canonicalPath
            if (canPath == opencodeBase || canPath.startsWith("$opencodeBase/")) {
                return resultFail(rawPath, normalized.toString(), canonicalPath, "Path is inside files/opencode")
            }
            if (canPath == supportBase || canPath.startsWith("$supportBase/")) {
                return resultFail(rawPath, normalized.toString(), canonicalPath, "Path is inside files/support")
            }
            if (canPath == storageBase || canPath.startsWith("$storageBase/")) {
                return resultFail(rawPath, normalized.toString(), canonicalPath, "Path is inside files/storage")
            }
        }

        val isValid = true
        val reason = if (!canWrite) "Directory valid (read-only mode)" else "Valid directory"

        return ProjectValidationResult(
            originalPath = rawPath,
            normalizedPath = normalized.toString(),
            canonicalPath = canonicalPath,
            isDirectory = true,
            canRead = canRead,
            canWrite = canWrite,
            canExecute = canExecute,
            isValid = isValid,
            reason = reason
        )
    }

    private fun String?.isNull_or_blank(): Boolean = this == null || this.trim().isEmpty()

    private fun resultFail(orig: String?, norm: String?, can: String?, reason: String): ProjectValidationResult {
        return ProjectValidationResult(
            originalPath = orig,
            normalizedPath = norm,
            canonicalPath = can,
            isDirectory = false,
            canRead = false,
            canWrite = false,
            canExecute = false,
            isValid = false,
            reason = reason
        )
    }
}
