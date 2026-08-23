package dev.ide.agent.impl.opencode

import kotlinx.serialization.Serializable

@Serializable
data class OpenCodeRegistryModel(
    val version: String = "1.0.0",
    val agents: List<OpenCodeAgentEntry> = emptyList(),
    val extensions: List<String> = emptyList()
)

@Serializable
data class OpenCodeAgentEntry(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val authors: List<String> = emptyList(),
    val license: String = "MIT",
    val distribution: OpenCodeDistribution = OpenCodeDistribution()
)

@Serializable
data class OpenCodeDistribution(
    val binary: Map<String, OpenCodeBinaryDist> = emptyMap()
)

@Serializable
data class OpenCodeBinaryDist(
    val archiveUrl: String = "",
    val sha256: String = "",
    val sizeBytes: Long = 0L,
    val architecture: String = "aarch64",
    val os: String = "linux",
    val license: String = "MIT",
    val sourceUrl: String = "",
    val verified: Boolean = false,
    val verificationStatus: String = "pending_artifact",
    val dependencies: List<String> = emptyList(),
    val relativePath: String = "",
    val cmd: String = "./opencode",
    val args: List<String> = listOf("web")
)
