package com.ai.notes.data.ai.chat

sealed class ChatMessage {
    data class FromUser(val text: String) : ChatMessage()
    data class FromAssistant(val text: String) : ChatMessage()
    data class ToolActivity(val functionName: String) : ChatMessage()
}
