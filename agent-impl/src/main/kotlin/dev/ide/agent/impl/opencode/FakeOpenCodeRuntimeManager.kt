package dev.ide.agent.impl.opencode

/**
 * Fake implementation of OpenCodeRuntimeManager for unit testing.
 * Performs zero process launches, zero socket binding, and records invocations in-memory.
 */
class FakeOpenCodeRuntimeManager(
    private val paths: OpenCodePaths,
    private val portAllocator: PortAllocator = PortAllocator()
) : OpenCodeRuntimeManager {

    val calls = mutableListOf<String>()
    private var activeHandle: OpenCodeProcessHandle? = null
    private var currentStatus: RuntimeStatus = RuntimeStatus.UNINITIALIZED

    override fun prepareDirectories(projectId: String) {
        calls.add("prepareDirectories($projectId)")
        paths.ensureDirectories()
        val projectState = paths.projectStateDir(projectId)
        if (!projectState.exists()) {
            projectState.mkdirs()
        }
        currentStatus = RuntimeStatus.READY
    }

    override fun validateRuntime(): Boolean {
        calls.add("validateRuntime()")
        return true
    }

    override fun allocatePort(): Int {
        calls.add("allocatePort()")
        return portAllocator.allocateAvailablePort()
    }

    override fun startServer(projectId: String, canonicalPath: String): Result<OpenCodeProcessHandle> {
        calls.add("startServer($projectId, $canonicalPath)")
        return Result.failure(IllegalStateException("Runtime non-installed in Phase 3A (Fake Manager)"))
    }

    override fun stopServer(handle: OpenCodeProcessHandle): Result<Unit> {
        calls.add("stopServer(${handle.projectId})")
        activeHandle = null
        currentStatus = RuntimeStatus.READY
        return Result.success(Unit)
    }

    override fun currentHandle(): OpenCodeProcessHandle? = activeHandle

    override fun status(): RuntimeStatus = currentStatus
}
