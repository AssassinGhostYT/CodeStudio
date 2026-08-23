package dev.ide.agent.impl.opencode

import kotlinx.serialization.json.Json

data class RegistryLookupResult(
    val isValid: Boolean,
    val reason: String,
    val binaryDist: OpenCodeBinaryDist? = null
)

object AgentRegistry {
    private val jsonInstance = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseRegistry(jsonContent: String): OpenCodeRegistryModel? {
        return runCatching {
            jsonInstance.decodeFromString<OpenCodeRegistryModel>(jsonContent)
        }.getOrNull()
    }

    fun resolveForHostAbi(
        registry: OpenCodeRegistryModel,
        agentId: String = "opencode",
        abiKey: String = "linux-aarch64"
    ): RegistryLookupResult {
        val agent = registry.agents.find { it.id == agentId }
            ?: return RegistryLookupResult(false, "Agent '$agentId' not found in registry")

        val dist = agent.distribution.binary[abiKey]
            ?: return RegistryLookupResult(false, "Distribution key '$abiKey' not found for agent '$agentId'")

        if (dist.archiveUrl.isBlank()) {
            return RegistryLookupResult(false, "archiveUrl is empty", dist)
        }

        if (dist.sha256.length != 64 || !dist.sha256.all { it.isLetterOrDigit() }) {
            return RegistryLookupResult(false, "Invalid or missing SHA-256 hash", dist)
        }

        if (!dist.verified || dist.verificationStatus != "verified") {
            return RegistryLookupResult(false, "Artifact is not verified for active installation (status: ${dist.verificationStatus})", dist)
        }

        return RegistryLookupResult(true, "Artifact verified and ready", dist)
    }
}
