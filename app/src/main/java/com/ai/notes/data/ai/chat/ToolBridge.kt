package com.ai.notes.data.ai.chat

import com.ai.notes.data.ai.model.ClaudeTool
import kotlinx.serialization.json.JsonObject

interface ToolBridge {
    suspend fun discoverTools(): List<ClaudeTool>
    suspend fun execute(functionName: String, input: JsonObject): ToolExecutionResult
}

sealed class ToolExecutionResult {
    data class Success(val resultJson: String) : ToolExecutionResult()
    data class Failure(val message: String) : ToolExecutionResult()
}
