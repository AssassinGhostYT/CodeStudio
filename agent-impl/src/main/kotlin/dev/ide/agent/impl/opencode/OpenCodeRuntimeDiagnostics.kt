package dev.ide.agent.impl.opencode

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class RuntimeComponentStatus(
    val componentName: String,
    val path: String,
    val exists: Boolean,
    val isRegularFile: Boolean,
    val isDirectory: Boolean,
    val canRead: Boolean,
    val canExecute: Boolean,
    val sizeBytes: Long,
    val sha256: String? = null,
    val expectedSha256: String? = null,
    val checksumMatches: Boolean? = null,
    val reason: String
)

data class OpenCodeRuntimeDiagnosticReport(
    val architecture: String,
    val runtimeRoot: String,
    val components: List<RuntimeComponentStatus>,
    val missingComponents: List<String>,
    val warnings: List<String>,
    val availablePort: Int? = null,
    val isReadyForManualRuntimeTest: Boolean = false
)

/**
 * Manual developer diagnostic. Performs strictly 100% passive inspection.
 * Does NOT execute binaries, shell commands, Proot, BusyBox, open HTTP/SSE,
 * or read secret/credential files.
 */
object OpenCodeRuntimeDiagnostics {

    fun runDiagnostic(
        baseFilesDir: File,
        expectedChecksums: Map<String, String> = emptyMap(),
        portAllocator: PortAllocator = PortAllocator()
    ): OpenCodeRuntimeDiagnosticReport {
        val paths = OpenCodePaths(baseFilesDir)
        val arch = System.getProperty("os.arch") ?: "unknown"
        val components = mutableListOf<RuntimeComponentStatus>()
        val missingComponents = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val expectedItems = listOf(
            "runtime/busybox" to false,
            "agents/opencode/1.18.18/opencode" to false,
            "state/projects" to true,
            "logs" to true,
            "registry" to true,
            "tmp" to true
        )

        for ((relPath, isDirExpected) in expectedItems) {
            val file = paths.resolveSubPath(relPath)
            val expectedSha = expectedChecksums[relPath]
            val status = inspectComponent(relPath, file, isDirExpected, expectedSha)
            components.add(status)
            if (!status.exists) {
                missingComponents.add(relPath)
            } else if (expectedSha != null && status.checksumMatches == false) {
                warnings.add("Checksum mismatch for component: $relPath")
            }
        }

        val availablePort = runCatching { portAllocator.allocateAvailablePort() }.getOrNull()
        if (availablePort == null) {
            warnings.add("No loopback port available in current range")
        }

        val isReady = missingComponents.isEmpty() && warnings.none { it.contains("Checksum mismatch") }

        return OpenCodeRuntimeDiagnosticReport(
            architecture = arch,
            runtimeRoot = paths.rootDir.absolutePath,
            components = components,
            missingComponents = missingComponents,
            warnings = warnings,
            availablePort = availablePort,
            isReadyForManualRuntimeTest = isReady
        )
    }

    fun inspectComponent(
        name: String,
        file: File,
        isDirExpected: Boolean,
        expectedSha256: String? = null
    ): RuntimeComponentStatus {
        if (!file.exists()) {
            return RuntimeComponentStatus(
                componentName = name,
                path = file.absolutePath,
                exists = false,
                isRegularFile = false,
                isDirectory = false,
                canRead = false,
                canExecute = false,
                sizeBytes = 0L,
                sha256 = null,
                expectedSha256 = expectedSha256,
                checksumMatches = null,
                reason = "Component missing"
            )
        }

        val isFile = file.isFile
        val isDir = file.isDirectory
        val canRead = file.canRead()
        val canExec = file.canExecute()
        val size = if (isFile) file.length() else 0L

        var calculatedSha: String? = null
        var matches: Boolean? = null

        if (isFile && expectedSha256 != null) {
            calculatedSha = computeSha256(file)
            matches = (calculatedSha == expectedSha256)
        }

        val reason = when {
            isDirExpected && !isDir -> "Expected directory but found file"
            !isDirExpected && !isFile -> "Expected regular file but found directory"
            !canRead -> "Component is not readable"
            !isDirExpected && !canExec -> "Binary component is not executable"
            matches == false -> "SHA-256 checksum mismatch"
            else -> "Component valid"
        }

        return RuntimeComponentStatus(
            componentName = name,
            path = file.absolutePath,
            exists = true,
            isRegularFile = isFile,
            isDirectory = isDir,
            canRead = canRead,
            canExecute = canExec,
            sizeBytes = size,
            sha256 = calculatedSha,
            expectedSha256 = expectedSha256,
            checksumMatches = matches,
            reason = reason
        )
    }

    fun computeSha256(file: File): String {
        if (!file.exists() || !file.isFile) return "FILE_NOT_FOUND"
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
