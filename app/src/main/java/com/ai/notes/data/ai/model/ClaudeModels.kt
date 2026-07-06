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
     */
    @Serializable
    @SerialName("thinking")
    data class Thinking(val thinking: String, val signature: String? = null) : ClaudeContentBlock()
}

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
    val tools: List<ClaudeTool>? = null
)

@Serializable
data class ClaudeResponse(
    val content: List<ClaudeContentBlock>,
    @SerialName("stop_reason") val stopReason: String? = null
)
