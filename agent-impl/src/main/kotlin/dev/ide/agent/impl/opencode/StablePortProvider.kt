package dev.ide.agent.impl.opencode

import java.io.File
import java.net.InetAddress

/**
 * Allocates a port for an opencode web server and persists it per project, so the opencode web
 * origin (host:port) stays stable across restarts. A changing port resets the browser's per-origin
 * session state, which makes chat history appear to be lost.
 */
object StablePortProvider {

    /**
     * Returns the persisted port for [projectId] when it is still free, otherwise allocates a fresh
     * one and persists it. [targetPort] bypasses persistence when it is an explicit value.
     */
    fun resolveStablePort(
        projectId: String,
        paths: OpenCodePaths,
        allocator: PortAllocator = PortAllocator(),
        targetPort: Int = 0
    ): Int {
        require(projectId.isNotBlank()) { "projectId must not be blank" }
        if (targetPort > 0) return targetPort

        val portsDir = paths.stateProjectsDir
        portsDir.mkdirs()
        val portFile = File(portsDir, "$projectId.port")

        val saved = runCatching { portFile.readText().trim() }.getOrNull()?.toIntOrNull()
        if (saved != null && saved in allocator.startPort..allocator.endPort &&
            allocator.isPortAvailable(InetAddress.getByName(allocator.host), saved)
        ) {
            return saved
        }

        val fresh = allocator.allocateAvailablePort()
        runCatching { portFile.writeText(fresh.toString()) }
        return fresh
    }
}