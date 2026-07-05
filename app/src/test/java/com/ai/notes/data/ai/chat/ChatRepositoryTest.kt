package com.ai.notes.data.ai.chat

import com.ai.notes.data.ai.AppError
import com.ai.notes.data.ai.ClaudeService
import com.ai.notes.data.ai.model.ClaudeContentBlock
import com.ai.notes.data.ai.model.ClaudeMessage
import com.ai.notes.data.ai.model.ClaudeRequest
import com.ai.notes.data.ai.model.ClaudeResponse
import com.ai.notes.data.preferences.ApiKeyManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

private fun apiKeyManager(key: String? = "sk-ant-key"): ApiKeyManager {
    val manager = mockk<ApiKeyManager>()
    every { manager.getApiKey() } returns key
    return manager
}

private fun textResponse(text: String) = Response.success(
    ClaudeResponse(content = listOf(ClaudeContentBlock.Text(text)), stopReason = "end_turn")
)

private fun toolUseResponse(id: String, name: String, input: JsonObject = JsonObject(emptyMap())) = Response.success(
    ClaudeResponse(
        content = listOf(ClaudeContentBlock.ToolUse(id = id, name = name, input = input)),
        stopReason = "tool_use",
    )
)

class ChatRepositoryTest {

    @Test
    fun `send returns Done with the plain text reply when no tool is used`() = runTest {
        val service = mockk<ClaudeService>()
        coEvery { service.sendMessage(any(), any(), any()) } returns textResponse("Hello there!")
        val toolBridge = mockk<ToolBridge>()
        coEvery { toolBridge.discoverTools() } returns emptyList()
        val repository = ChatRepository(service, apiKeyManager(), toolBridge)

        val result = repository.send(listOf(ClaudeMessage("user", listOf(ClaudeContentBlock.Text("Hi")))))

        assertTrue(result is ChatTurnResult.Done)
        assertEquals("Hello there!", (result as ChatTurnResult.Done).reply)
        assertTrue(result.toolCalls.isEmpty())
    }

    @Test
    fun `send executes a non-destructive tool then returns the final text`() = runTest {
        val service = mockk<ClaudeService>()
        coEvery { service.sendMessage(any(), any(), any()) } returnsMany listOf(
            toolUseResponse("t1", "searchNotes", JsonObject(mapOf("query" to JsonPrimitive("milk")))),
            textResponse("Found 1 note about milk."),
        )
        val toolBridge = mockk<ToolBridge>()
        coEvery { toolBridge.discoverTools() } returns emptyList()
        coEvery { toolBridge.execute("searchNotes", any()) } returns ToolExecutionResult.Success("[]")
        val repository = ChatRepository(service, apiKeyManager(), toolBridge)

        val result = repository.send(listOf(ClaudeMessage("user", listOf(ClaudeContentBlock.Text("find milk note")))))

        assertTrue(result is ChatTurnResult.Done)
        assertEquals("Found 1 note about milk.", (result as ChatTurnResult.Done).reply)
        assertEquals(listOf("searchNotes"), result.toolCalls)
        coVerify(exactly = 1) { toolBridge.execute("searchNotes", any()) }
    }

    @Test
    fun `send pauses for confirmation on deleteNote without executing it`() = runTest {
        val service = mockk<ClaudeService>()
        coEvery { service.sendMessage(any(), any(), any()) } returns
            toolUseResponse("t1", "deleteNote", JsonObject(mapOf("noteId" to JsonPrimitive(5))))
        val toolBridge = mockk<ToolBridge>()
        coEvery { toolBridge.discoverTools() } returns emptyList()
        val repository = ChatRepository(service, apiKeyManager(), toolBridge)

        val result = repository.send(listOf(ClaudeMessage("user", listOf(ClaudeContentBlock.Text("delete note 5")))))

        assertTrue(result is ChatTurnResult.NeedsConfirmation)
        val confirmation = result as ChatTurnResult.NeedsConfirmation
        assertEquals("deleteNote", confirmation.pending.functionName)
        assertEquals(listOf("deleteNote"), confirmation.toolCalls)
        coVerify(exactly = 0) { toolBridge.execute("deleteNote", any()) }
    }

    @Test
    fun `resolveConfirmation with approved true executes the held tool and continues`() = runTest {
        val service = mockk<ClaudeService>()
        coEvery { service.sendMessage(any(), any(), any()) } returns textResponse("Deleted it.")
        val toolBridge = mockk<ToolBridge>()
        coEvery { toolBridge.discoverTools() } returns emptyList()
        coEvery { toolBridge.execute("deleteNote", any()) } returns ToolExecutionResult.Success("true")
        val repository = ChatRepository(service, apiKeyManager(), toolBridge)
        val pending = PendingToolUse("t1", "deleteNote", JsonObject(mapOf("noteId" to JsonPrimitive(5))))

        val result = repository.resolveConfirmation(
            history = listOf(ClaudeMessage("user", listOf(ClaudeContentBlock.Text("delete note 5")))),
            pending = pending,
            resolvedResults = emptyList(),
            approved = true,
        )

        assertTrue(result is ChatTurnResult.Done)
        assertEquals("Deleted it.", (result as ChatTurnResult.Done).reply)
        coVerify(exactly = 1) { toolBridge.execute("deleteNote", any()) }
    }

    @Test
    fun `resolveConfirmation with approved false does not execute and continues with a decline result`() = runTest {
        val service = mockk<ClaudeService>()
        val requestSlot = slot<ClaudeRequest>()
        coEvery { service.sendMessage(any(), any(), capture(requestSlot)) } returns textResponse("Okay, keeping it.")
        val toolBridge = mockk<ToolBridge>()
        coEvery { toolBridge.discoverTools() } returns emptyList()
        val repository = ChatRepository(service, apiKeyManager(), toolBridge)
        val pending = PendingToolUse("t1", "deleteNote", JsonObject(mapOf("noteId" to JsonPrimitive(5))))

        val result = repository.resolveConfirmation(
            history = listOf(ClaudeMessage("user", listOf(ClaudeContentBlock.Text("delete note 5")))),
            pending = pending,
            resolvedResults = emptyList(),
            approved = false,
        )

        assertTrue(result is ChatTurnResult.Done)
        coVerify(exactly = 0) { toolBridge.execute("deleteNote", any()) }
        val lastMessageBlocks = requestSlot.captured.messages.last().content
        val toolResult = lastMessageBlocks.single() as ClaudeContentBlock.ToolResult
        assertTrue(toolResult.isError)
        assertEquals("User declined this action.", toolResult.content)
    }

    @Test
    fun `resolveConfirmation combines already-resolved results with the confirmed one`() = runTest {
        val service = mockk<ClaudeService>()
        val requestSlot = slot<ClaudeRequest>()
        coEvery { service.sendMessage(any(), any(), capture(requestSlot)) } returns textResponse("Done.")
        val toolBridge = mockk<ToolBridge>()
        coEvery { toolBridge.discoverTools() } returns emptyList()
        coEvery { toolBridge.execute("deleteNote", any()) } returns ToolExecutionResult.Success("true")
        val repository = ChatRepository(service, apiKeyManager(), toolBridge)
        val alreadyResolved = listOf(
            ClaudeContentBlock.ToolResult(toolUseId = "t0", content = "[{\"id\":5}]"),
        )
        val pending = PendingToolUse("t1", "deleteNote", JsonObject(mapOf("noteId" to JsonPrimitive(5))))

        repository.resolveConfirmation(
            history = listOf(ClaudeMessage("user", listOf(ClaudeContentBlock.Text("find and delete note 5")))),
            pending = pending,
            resolvedResults = alreadyResolved,
            approved = true,
        )

        val lastMessageBlocks = requestSlot.captured.messages.last().content
        assertEquals(2, lastMessageBlocks.size)
        assertEquals("t0", (lastMessageBlocks[0] as ClaudeContentBlock.ToolResult).toolUseId)
        assertEquals("t1", (lastMessageBlocks[1] as ClaudeContentBlock.ToolResult).toolUseId)
    }

    @Test
    fun `send maps a tool execution Failure to an isError tool_result and continues`() = runTest {
        val service = mockk<ClaudeService>()
        val requestSlot = slot<ClaudeRequest>()
        coEvery { service.sendMessage(any(), any(), capture(requestSlot)) } returnsMany listOf(
            toolUseResponse("t1", "searchNotes"),
            textResponse("Sorry, I couldn't search."),
        )
        val toolBridge = mockk<ToolBridge>()
        coEvery { toolBridge.discoverTools() } returns emptyList()
        coEvery { toolBridge.execute("searchNotes", any()) } returns ToolExecutionResult.Failure("boom")
        val repository = ChatRepository(service, apiKeyManager(), toolBridge)

        val result = repository.send(listOf(ClaudeMessage("user", listOf(ClaudeContentBlock.Text("search")))))

        assertTrue(result is ChatTurnResult.Done)
        val toolResult = requestSlot.captured.messages.last().content.single() as ClaudeContentBlock.ToolResult
        assertTrue(toolResult.isError)
        assertEquals("boom", toolResult.content)
    }

    @Test
    fun `send returns an apologetic Done after exceeding the iteration cap`() = runTest {
        val service = mockk<ClaudeService>()
        coEvery { service.sendMessage(any(), any(), any()) } returns toolUseResponse("t1", "searchNotes")
        val toolBridge = mockk<ToolBridge>()
        coEvery { toolBridge.discoverTools() } returns emptyList()
        coEvery { toolBridge.execute("searchNotes", any()) } returns ToolExecutionResult.Success("[]")
        val repository = ChatRepository(service, apiKeyManager(), toolBridge)

        val result = repository.send(listOf(ClaudeMessage("user", listOf(ClaudeContentBlock.Text("loop forever")))))

        assertTrue(result is ChatTurnResult.Done)
        coVerify(exactly = 5) { toolBridge.execute("searchNotes", any()) }
    }

    @Test
    fun `send returns Error InvalidApiKey when no key is stored`() = runTest {
        val repository = ChatRepository(mockk<ClaudeService>(), apiKeyManager(null), mockk<ToolBridge>())

        val result = repository.send(listOf(ClaudeMessage("user", listOf(ClaudeContentBlock.Text("hi")))))

        assertTrue(result is ChatTurnResult.Error)
        assertEquals(AppError.InvalidApiKey, (result as ChatTurnResult.Error).error)
    }
}
