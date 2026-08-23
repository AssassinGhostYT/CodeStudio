package dev.ide.agent.impl.opencode

import dev.ide.agent.ContentPart
import dev.ide.agent.LlmClient
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmModelInfo
import dev.ide.agent.LlmProvider
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmRole
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.ProviderConfig
import dev.ide.agent.StopReason
import dev.ide.agent.TokenUsage
import dev.ide.agent.impl.AgentJson
import dev.ide.agent.impl.LlmTransport
import dev.ide.agent.impl.OpenAiProvider
import dev.ide.agent.impl.OpenAiStreamDecoder
import dev.ide.agent.impl.SseRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * OpenCode Zen Provider.
 * Provider ID: "opencode"
 * Default Base URL: "https://opencode.ai/zen/v1"
 * API Key Environment Variable: OPENCODE_API_KEY
 * Free tier: connects without Authorization header when key is blank/optional.
 */
class OpenCodeProvider(private val transport: LlmTransport) : LlmProvider {

    override val id: String = "opencode"
    override val displayName: String = "OpenCode Zen"
    
    override val models: List<LlmModelInfo> = listOf(
        LlmModelInfo("deepseek-v4-flash-free", "DeepSeek V4 Flash Free"),
        LlmModelInfo("coding-glm-5.1-free", "Coding GLM 5.1 (free)"),
        LlmModelInfo("coding-minimax-m2.7-free", "Coding MiniMax M2.7 (Free)"),
        LlmModelInfo("glm-4.7-free", "GLM-4.7 Free"),
        LlmModelInfo("glm-5-free", "GLM-5 Free"),
        LlmModelInfo("hy3-free", "Hy3 Free"),
        LlmModelInfo("hy3-preview-free", "Hy3 preview Free"),
        LlmModelInfo("kimi-k2.5-free", "Kimi K2.5 Free"),
        LlmModelInfo("laguna-s-2.1-free", "Laguna S 2.1 Free"),
        LlmModelInfo("ling-2.6-flash-free", "Ling 2.6 Flash Free"),
        LlmModelInfo("ling-3.0-flash-free", "Ling-3.0-flash Free"),
        LlmModelInfo("ling-3.0-tiny-free", "Ling-3.0-tiny Free"),
        LlmModelInfo("longcat-2.0-free", "LongCat-2.0 Free"),
        LlmModelInfo("mimo-v2-flash-free", "MiMo V2 Flash Free"),
        LlmModelInfo("mimo-v2-omni-free", "MiMo V2 Omni Free"),
        LlmModelInfo("mimo-v2-pro-free", "MiMo V2 Pro Free"),
        LlmModelInfo("mimo-v2.5-free", "MiMo V2.5 Free"),
        LlmModelInfo("minimax-m2.1-free", "MiniMax-M2.1 Free"),
        LlmModelInfo("minimax-m2.5-free", "MiniMax-M2.5 Free"),
        LlmModelInfo("minimax-m3-free", "MiniMax-M3 Free"),
        LlmModelInfo("nemotron-3-super-free", "Nemotron 3 Super Free"),
        LlmModelInfo("nemotron-3-ultra-free", "Nemotron 3 Ultra Free"),
        LlmModelInfo("nemotron-3.5-lightning-free", "Nemotron 3.5 Lightning Free"),
        LlmModelInfo("north-mini-code-free", "North Mini Code Free"),
        LlmModelInfo("qwen3.6-plus-free", "Qwen3.6 Plus Free"),
        LlmModelInfo("ring-2.6-1t-free", "Ring 2.6 1T Free"),
        LlmModelInfo("trinity-large-preview-free", "Trinity Large Preview"),
        LlmModelInfo("xiaomi-mimo-v2.5-free", "Xiaomi MiMo-V2.5 (free)"),
        LlmModelInfo("xiaomi-mimo-v2.5-pro-free", "Xiaomi MiMo-V2.5-Pro (free)")
    )
    
    override val defaultModel: String = "deepseek-v4-flash-free"

    override fun client(config: ProviderConfig): LlmClient = LlmClient { request ->
        val base = config.baseUrl?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: DEFAULT_BASE
        val cleanModel = request.model.removePrefix("opencode/")
        
        val headers = mutableMapOf("content-type" to "application/json")
        if (config.apiKey.isNotBlank()) {
            headers["Authorization"] = "Bearer ${config.apiKey}"
        }
        
        val body = buildJsonObject {
            put("model", cleanModel)
            put("stream", true)
            put("max_tokens", request.maxTokens)
            put("messages", messages(request.system, request.messages))
            if (request.tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    request.tools.forEach { spec ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", spec.name)
                                put("description", spec.description)
                                put("parameters", AgentJson.parseToJsonElement(spec.parameters))
                            })
                        })
                    }
                })
            }
        }.toString()

        val sse = SseRequest(
            url = "$base/chat/completions",
            headers = headers,
            jsonBody = body,
            caCertificatePem = config.caCertificatePem,
        )
        stream(sse)
    }

    private val openai = OpenAiProvider(transport)

    override suspend fun listModels(config: ProviderConfig): List<LlmModelInfo> = runCatching {
        val base = config.baseUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE
        val fetched: List<LlmModelInfo> = openai.listModels(config.copy(baseUrl = base))
        val mapped: List<LlmModelInfo> = fetched.map { m ->
            val cleanId = m.id.removePrefix("opencode/")
            LlmModelInfo(cleanId, m.displayName.ifBlank { cleanId })
        }
        mapped.ifEmpty { models }
    }.getOrDefault(models)

    private fun stream(sse: SseRequest): Flow<LlmStreamEvent> = flow {
        var attempts = 0
        while (true) {
            val decoder = OpenAiStreamDecoder()
            try {
                transport.sse(sse).collect { data -> decoder.decode(data).forEach { emit(it) } }
                if (!decoder.completed) decoder.finish().forEach { emit(it) }
                return@flow
            } catch (e: Exception) {
                // The Zen free tier rate-limits per IP; retry with backoff instead of failing the turn.
                if (attempts >= MAX_RATE_LIMIT_RETRIES || !isRateLimit(e)) throw e
                attempts++
                delay(RATE_LIMIT_RETRY_BASE_MS * (1L shl attempts))
            }
        }
    }.catch { e -> emit(LlmStreamEvent.Failed(e.message ?: "OpenCode stream error", e)) }

    private fun isRateLimit(e: Exception): Boolean {
        val message = e.message?.lowercase() ?: return false
        return "rate limit" in message || "429" in message || "too many requests" in message
    }

    private fun messages(system: String?, messages: List<LlmMessage>): JsonArray = buildJsonArray {
        system?.takeIf { it.isNotBlank() }?.let { add(buildJsonObject { put("role", "system"); put("content", it) }) }
        messages.forEach { m ->
            when (m.role) {
                LlmRole.SYSTEM -> add(buildJsonObject { put("role", "system"); put("content", plainText(m.content)) })
                LlmRole.USER -> add(buildJsonObject { put("role", "user"); put("content", plainText(m.content)) })
                LlmRole.ASSISTANT -> add(assistantMessage(m.content))
                LlmRole.TOOL -> m.content.forEach { part ->
                    if (part is ContentPart.ToolResultPart) add(buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", part.toolCallId)
                        put("content", part.content)
                    })
                }
            }
        }
    }

    private fun assistantMessage(parts: List<ContentPart>) = buildJsonObject {
        put("role", "assistant")
        val text = parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
        val toolUses = parts.filterIsInstance<ContentPart.ToolUse>()
        if (text.isNotEmpty()) put("content", text)
        if (toolUses.isNotEmpty()) {
            put("tool_calls", buildJsonArray {
                toolUses.forEach { tu ->
                    add(buildJsonObject {
                        put("id", tu.id)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tu.name)
                            put("arguments", tu.arguments.ifBlank { "{}" })
                        })
                    })
                }
            })
        }
        if (text.isEmpty() && toolUses.isEmpty()) put("content", "")
    }

    private fun plainText(parts: List<ContentPart>): String =
        parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }

    companion object {
        const val DEFAULT_BASE = "https://opencode.ai/zen/v1"

        /** How many times a rate-limited (429) Zen request is retried with exponential backoff. */
        const val MAX_RATE_LIMIT_RETRIES = 3
        /** Base delay (ms) for the first rate-limit retry; each retry doubles it (2s, 4s, 8s). */
        const val RATE_LIMIT_RETRY_BASE_MS = 2_000L
    }
}
