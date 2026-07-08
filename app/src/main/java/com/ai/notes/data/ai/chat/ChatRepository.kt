package com.ai.notes.data.ai.chat

import com.ai.notes.data.ai.AppError
import com.ai.notes.data.ai.CLAUDE_API_VERSION
import com.ai.notes.data.ai.CLAUDE_MODEL
import com.ai.notes.data.ai.ClaudeService
import com.ai.notes.data.ai.ErrorMapper
import com.ai.notes.data.ai.model.ClaudeContentBlock
import com.ai.notes.data.ai.model.ClaudeMessage
import com.ai.notes.data.ai.model.ClaudeRequest
import com.ai.notes.data.ai.model.ClaudeThinkingConfig
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

    /**
     * [history] is the last structurally valid wire history (every tool_use paired with a
     * tool_result). Callers must adopt it: after a confirmed tool executes but the follow-up
     * request fails, the pre-request history ends in an unanswered tool_use, and reusing it
     * makes every later request fail with a 400.
     */
    data class Error(val error: AppError, val history: List<ClaudeMessage>) : ChatTurnResult()
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
        if (apiKey.isNullOrEmpty()) return ChatTurnResult.Error(AppError.InvalidApiKey, startHistory)

        var history = startHistory
        val toolCallNames = mutableListOf<String>()
        val tools = toolBridge.discoverTools().ifEmpty { null }

        repeat(MAX_ITERATIONS) {
            val request = ClaudeRequest(
                model = CLAUDE_MODEL,
                maxTokens = 2000,
                messages = history,
                tools = tools,
                thinking = ClaudeThinkingConfig("disabled"),
            )
            // At every error return below, `history` ends with a user message (the original
            // text or the previous round's tool results), so it stays valid to resume from.
            val response = try {
                claudeService.sendMessage(apiKey, CLAUDE_API_VERSION, request)
            } catch (t: Throwable) {
                return ChatTurnResult.Error(ErrorMapper.mapThrowable(t), history)
            }
            if (!response.isSuccessful) {
                return ChatTurnResult.Error(ErrorMapper.mapHttpCode(response.code()), history)
            }
            val body = response.body() ?: return ChatTurnResult.Error(AppError.UnknownNetwork, history)
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
