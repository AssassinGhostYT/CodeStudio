package dev.ide.agent.impl.opencode

enum class RuntimeStatus {
    UNINITIALIZED, READY, RUNNING, ERROR
}

/**
 * Interface contract for OpenCode runtime management.
 * In Phase 3A, no real PRoot execution is implemented.
 */
interface OpenCodeRuntimeManager {
    fun prepareDirectories(projectId: String)
    fun validateRuntime(): Boolean
    fun allocatePort(): Int
    fun startServer(projectId: String, canonicalPath: String): Result<OpenCodeProcessHandle>
    fun stopServer(handle: OpenCodeProcessHandle): Result<Unit>
    fun currentHandle(): OpenCodeProcessHandle?
    fun status(): RuntimeStatus
}
