package com.ai.notes.data.ai

import com.ai.notes.data.ai.model.ClaudeContentBlock
import com.ai.notes.data.ai.model.ClaudeMessage
import com.ai.notes.data.ai.model.ClaudeRequest
import com.ai.notes.data.ai.model.ClaudeThinkingConfig
import com.ai.notes.data.model.Note
import com.ai.notes.data.preferences.ApiKeyManager

sealed class SummarizeResult {
    data class Success(val summary: String) : SummarizeResult()
    data class Failure(val error: AppError) : SummarizeResult()
}

class SummarizationRepository(
    private val claudeService: ClaudeService,
    private val apiKeyManager: ApiKeyManager
) {
    suspend fun summarize(notes: List<Note>): SummarizeResult {
        val apiKey = apiKeyManager.getApiKey()
        if (apiKey.isNullOrEmpty()) {
            return SummarizeResult.Failure(AppError.InvalidApiKey)
        }

        val prompt = BatchSummarizer.buildPrompt(notes)
        val request = ClaudeRequest(
            model = CLAUDE_MODEL,
            maxTokens = 2000,
            messages = listOf(ClaudeMessage(role = "user", content = listOf(ClaudeContentBlock.Text(prompt)))),
            thinking = ClaudeThinkingConfig("disabled"),
        )

        return try {
            val response = claudeService.sendMessage(apiKey, CLAUDE_API_VERSION, request)
            if (response.isSuccessful) {
                val text = response.body()?.content
                    ?.filterIsInstance<ClaudeContentBlock.Text>()
                    ?.firstOrNull()
                    ?.text
                if (text != null) {
                    SummarizeResult.Success(text)
                } else {
                    SummarizeResult.Failure(AppError.UnknownNetwork)
                }
            } else {
                SummarizeResult.Failure(ErrorMapper.mapHttpCode(response.code()))
            }
        } catch (t: Throwable) {
            SummarizeResult.Failure(ErrorMapper.mapThrowable(t))
        }
    }
}
