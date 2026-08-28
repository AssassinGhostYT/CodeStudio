package dev.ide.agent.impl.opencode

enum class ProcessState {
    IDLE, STARTING, RUNNING, STOPPED, FAILED
}

/**
 * Immutable metadata handle of a process instance managed by CodeStudio.
 * Does not perform process startup, shutdown, or signal handling.
 */
data class OpenCodeProcessHandle(
    val projectId: String,
    val port: Int,
    val pid: Long? = null,
    val processGroup: Long? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val state: ProcessState = ProcessState.IDLE,
    val wrapperProcess: Process? = null
)
