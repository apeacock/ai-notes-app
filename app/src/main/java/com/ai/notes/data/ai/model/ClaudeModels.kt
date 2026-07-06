package com.ai.notes.data.ai.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ClaudeMessage(
    val role: String,
    val content: List<ClaudeContentBlock>
)

@Serializable
sealed class ClaudeContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ClaudeContentBlock()

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonObject
    ) : ClaudeContentBlock()

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        @SerialName("tool_use_id") val toolUseId: String,
        val content: String,
        @SerialName("is_error") val isError: Boolean = false
    ) : ClaudeContentBlock()

    /**
     * Extended-thinking block some models (e.g. claude-sonnet-5) emit alongside tool_use/text
     * blocks. `signature` must be preserved and echoed back verbatim when this block is included
     * in a later request's conversation history — Anthropic's API rejects the request with a 400
     * if it's dropped, which is what an earlier version of this class did by omitting the field.
     *
     * Requests from this app explicitly disable thinking (see [ClaudeThinkingConfig]), but a
     * response block is still modeled defensively in case a future model/API change re-enables
     * it unexpectedly — an unmodeled block type fails deserialization for the whole response,
     * which is exactly the class of bug that produced this comment.
     */
    @Serializable
    @SerialName("thinking")
    data class Thinking(val thinking: String, val signature: String? = null) : ClaudeContentBlock()

    /** Anthropic emits this in place of [Thinking] when reasoning trips a safety classifier. */
    @Serializable
    @SerialName("redacted_thinking")
    data class RedactedThinking(val data: String) : ClaudeContentBlock()
}

/** `{"type":"disabled"}` turns off extended thinking; this app has no use for it. */
@Serializable
data class ClaudeThinkingConfig(val type: String)

@Serializable
data class ClaudeTool(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject
)

@Serializable
data class ClaudeRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<ClaudeMessage>,
    val tools: List<ClaudeTool>? = null,
    val thinking: ClaudeThinkingConfig,
)

@Serializable
data class ClaudeResponse(
    val content: List<ClaudeContentBlock>,
    @SerialName("stop_reason") val stopReason: String? = null
)
