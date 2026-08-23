package dev.ide.agent.impl.opencode

import java.io.File

data class ScriptValidationResult(
    val isValid: Boolean,
    val reason: String,
    val detectedSecrets: List<String> = emptyList()
)

object ScriptValidator {

    private val secretPatterns = listOf(
        Regex("(?i)api[_-]?key\\s*=\\s*['\"]?[A-Za-z0-9_\\-]{16,}['\"]?"),
        Regex("(?i)bearer\\s+[A-Za-z0-9_\\-\\.]+"),
        Regex("(?i)secret[_-]?token\\s*=\\s*['\"]?[A-Za-z0-9_\\-]{16,}['\"]?"),
        Regex("(?i)password\\s*=\\s*['\"]?[^'\"\\s]{8,}['\"]?")
    )

    fun validateScriptFile(scriptFile: File): ScriptValidationResult {
        if (!scriptFile.exists()) {
            return ScriptValidationResult(false, "Script file does not exist")
        }
        if (!scriptFile.isFile) {
            return ScriptValidationResult(false, "Script is not a regular file")
        }

        val text = runCatching { scriptFile.readText(Charsets.UTF_8) }.getOrElse {
            return ScriptValidationResult(false, "Failed to read script as UTF-8 text")
        }

        val foundSecrets = mutableListOf<String>()
        for (pattern in secretPatterns) {
            val matches = pattern.findAll(text)
            for (match in matches) {
                foundSecrets.add(match.value)
            }
        }

        if (foundSecrets.isNotEmpty()) {
            return ScriptValidationResult(false, "Script contains sensitive secrets/keys", foundSecrets)
        }

        return ScriptValidationResult(true, "Script is valid and contains no hardcoded secrets")
    }
}
