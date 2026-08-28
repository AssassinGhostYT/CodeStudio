package dev.ide.agent

/**
 * Immutable session binding record between a CodeStudio project and OpenCode.
 * Pure DTO model for future persistence mapping.
 */
data class OpenCodeSessionRecord(
    val schemaVersion: Int = 1,
    val projectId: String,
    val canonicalPath: String,
    val displayName: String,
    val openCodeSessionId: String? = null,
    val activeModel: String? = null,
    val openCodeVersion: String? = null,
    val lastKnownProjectEpoch: Int = 0,
    val lastActiveTimestamp: Long = System.currentTimeMillis()
)
