package com.ai.notes.data.ai.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ClaudeMessage(
    val role: String,
    val content: String
)

@Serializable
data class ClaudeRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<ClaudeMessage>
)

@Serializable
data class ClaudeContentBlock(
    val type: String,
    val text: String
)

@Serializable
data class ClaudeResponse(
    val content: List<ClaudeContentBlock>
)
