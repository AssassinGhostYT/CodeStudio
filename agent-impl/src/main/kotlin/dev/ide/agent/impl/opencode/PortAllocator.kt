package dev.ide.agent.impl.opencode

import java.net.InetAddress
import java.net.ServerSocket

/**
 * Dynamically finds an available loopback port within a configurable range on 127.0.0.1.
 * Note: Brief check-and-close race condition exists between allocation and process binding.
 */
class PortAllocator(
    val host: String = "127.0.0.1",
    val startPort: Int = 4098,
    val endPort: Int = 4198
) {
    init {
        require(startPort in 1024..65535) { "startPort out of range: $startPort" }
        require(endPort in startPort..65535) { "endPort out of range: $endPort" }
    }

    fun allocateAvailablePort(): Int {
        val inetAddress = InetAddress.getByName(host)
        for (port in startPort..endPort) {
            if (isPortAvailable(inetAddress, port)) {
                return port
            }
        }
        throw IllegalStateException("No available ports found in range $startPort..$endPort on $host")
    }

    fun isPortAvailable(inetAddress: InetAddress, port: Int): Boolean {
        return try {
            ServerSocket(port, 1, inetAddress).use { socket ->
                socket.reuseAddress = true
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
