package com.ai.notes.data.ai

import com.ai.notes.data.ai.model.ClaudeRequest
import com.ai.notes.data.ai.model.ClaudeResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

const val CLAUDE_BASE_URL = "https://api.anthropic.com/"
const val CLAUDE_MODEL = "claude-sonnet-5"
const val CLAUDE_API_VERSION = "2023-06-01"

interface ClaudeService {
    @POST("v1/messages")
    suspend fun sendMessage(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = CLAUDE_API_VERSION,
        @Body request: ClaudeRequest
    ): Response<ClaudeResponse>
}
