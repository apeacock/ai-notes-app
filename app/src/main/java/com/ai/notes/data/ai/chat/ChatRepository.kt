package com.ai.notes.data.ai.chat

import com.ai.notes.data.ai.AppError
import com.ai.notes.data.ai.CLAUDE_API_VERSION
import com.ai.notes.data.ai.CLAUDE_MODEL
import com.ai.notes.data.ai.ClaudeService
import com.ai.notes.data.ai.ErrorMapper
import com.ai.notes.data.ai.model.ClaudeContentBlock
import com.ai.notes.data.ai.model.ClaudeMessage
import com.ai.notes.data.ai.model.ClaudeRequest
import com.ai.notes.data.preferences.ApiKeyManager
import kotlinx.serialization.json.JsonObject

data class PendingToolUse(
    val toolUseId: String,
    val functionName: String,
    val input: JsonObject,
)

sealed class ChatTurnResult {
    data class Done(
        val history: List<ClaudeMessage>,
        val reply: String,
        val toolCalls: List<String>,
    ) : ChatTurnResult()

    data class NeedsConfirmation(
        val history: List<ClaudeMessage>,
        val pending: PendingToolUse,
        val resolvedResults: List<ClaudeContentBlock.ToolResult>,
        val toolCalls: List<String>,
    ) : ChatTurnResult()

    data class Error(val error: AppError) : ChatTurnResult()
}

/**
 * Runs Claude's agentic tool-use loop: send history + tool schemas, execute any tool the model
 * calls via [toolBridge] (pausing for [DESTRUCTIVE_FUNCTIONS] until the caller confirms), feed
 * results back, repeat until Claude returns a final text reply.
 */
class ChatRepository(
    private val claudeService: ClaudeService,
    private val apiKeyManager: ApiKeyManager,
    private val toolBridge: ToolBridge,
) {
    suspend fun send(history: List<ClaudeMessage>): ChatTurnResult = runLoop(history)

    suspend fun resolveConfirmation(
        history: List<ClaudeMessage>,
        pending: PendingToolUse,
        resolvedResults: List<ClaudeContentBlock.ToolResult>,
        approved: Boolean,
    ): ChatTurnResult {
        val pendingResult = if (approved) {
            toToolResultBlock(pending, toolBridge.execute(pending.functionName, pending.input))
        } else {
            ClaudeContentBlock.ToolResult(
                toolUseId = pending.toolUseId,
                content = "User declined this action.",
                isError = true,
            )
        }
        val nextHistory = history + ClaudeMessage(role = "user", content = resolvedResults + pendingResult)
        return runLoop(nextHistory)
    }

    private suspend fun runLoop(startHistory: List<ClaudeMessage>): ChatTurnResult {
        val apiKey = apiKeyManager.getApiKey()
        if (apiKey.isNullOrEmpty()) return ChatTurnResult.Error(AppError.InvalidApiKey)

        var history = startHistory
        val toolCallNames = mutableListOf<String>()
        val tools = toolBridge.discoverTools().ifEmpty { null }

        repeat(MAX_ITERATIONS) {
            val request = ClaudeRequest(
                model = CLAUDE_MODEL,
                maxTokens = 1024,
                messages = history,
                tools = tools,
            )
            val response = try {
                claudeService.sendMessage(apiKey, CLAUDE_API_VERSION, request)
            } catch (t: Throwable) {
                return ChatTurnResult.Error(ErrorMapper.mapThrowable(t))
            }
            if (!response.isSuccessful) {
                return ChatTurnResult.Error(ErrorMapper.mapHttpCode(response.code()))
            }
            val body = response.body() ?: return ChatTurnResult.Error(AppError.UnknownNetwork)
            history = history + ClaudeMessage(role = "assistant", content = body.content)

            val toolUses = body.content.filterIsInstance<ClaudeContentBlock.ToolUse>()
            if (body.stopReason != "tool_use" || toolUses.isEmpty()) {
                val reply = body.content.filterIsInstance<ClaudeContentBlock.Text>()
                    .joinToString("\n") { it.text }
                return ChatTurnResult.Done(history, reply, toolCallNames)
            }
            toolCallNames += toolUses.map { it.name }

            val destructive = toolUses.firstOrNull { it.name in DESTRUCTIVE_FUNCTIONS }
            if (destructive != null) {
                // Execute every other tool call from this same turn immediately; Claude expects a
                // tool_result for every tool_use id, so these can't be silently dropped while we
                // wait on confirmation for the destructive one.
                val resolvedResults = toolUses.filter { it.id != destructive.id }.map { toolUse ->
                    toToolResultBlock(
                        PendingToolUse(toolUse.id, toolUse.name, toolUse.input),
                        toolBridge.execute(toolUse.name, toolUse.input),
                    )
                }
                return ChatTurnResult.NeedsConfirmation(
                    history,
                    PendingToolUse(destructive.id, destructive.name, destructive.input),
                    resolvedResults,
                    toolCallNames,
                )
            }

            val resultBlocks = toolUses.map { toolUse ->
                toToolResultBlock(
                    PendingToolUse(toolUse.id, toolUse.name, toolUse.input),
                    toolBridge.execute(toolUse.name, toolUse.input),
                )
            }
            history = history + ClaudeMessage(role = "user", content = resultBlocks)
        }
        return ChatTurnResult.Done(history, "Sorry, I couldn't resolve that.", toolCallNames)
    }

    private fun toToolResultBlock(
        pending: PendingToolUse,
        result: ToolExecutionResult,
    ): ClaudeContentBlock.ToolResult = when (result) {
        is ToolExecutionResult.Success -> ClaudeContentBlock.ToolResult(
            toolUseId = pending.toolUseId,
            content = result.resultJson,
        )
        is ToolExecutionResult.Failure -> ClaudeContentBlock.ToolResult(
            toolUseId = pending.toolUseId,
            content = result.message,
            isError = true,
        )
    }

    private companion object {
        const val MAX_ITERATIONS = 5
        val DESTRUCTIVE_FUNCTIONS = setOf("deleteNote")
    }
}
