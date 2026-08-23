package dev.ide.agent.impl.opencode

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Generates a stable, canonical projectId SHA-256 hash.
 * Moving or renaming a project directory produces a different projectId.
 */
object ProjectIdGenerator {

    fun generateProjectId(canonicalOrNormalizedPath: String): String {
        require(canonicalOrNormalizedPath.isNotBlank()) { "Path must not be blank" }
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(canonicalOrNormalizedPath.toByteArray(StandardCharsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
