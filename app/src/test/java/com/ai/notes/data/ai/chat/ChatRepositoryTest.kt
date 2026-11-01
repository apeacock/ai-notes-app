package com.ai.notes.data.ai.chat

import com.ai.notes.data.ai.AppError
import com.ai.notes.data.ai.CLAUDE_API_VERSION
import com.ai.notes.data.ai.CLAUDE_MODEL
import com.ai.notes.data.ai.ClaudeService
import com.ai.notes.data.ai.model.ClaudeContentBlock
import com.ai.notes.data.ai.model.ClaudeMessage
import com.ai.notes.data.ai.model.ClaudeRequest
import com.ai.notes.data.ai.model.ClaudeResponse
import com.ai.notes.data.ai.model.ClaudeThinkingConfig
import com.ai.notes.data.ai.model.ClaudeTool
import com.ai.notes.data.preferences.ApiKeyManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.net.SocketTimeoutException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `resolveConfirmation returns Error carrying the resolved history when the follow-up request fails`() = runTest {
        val service = mockk<ClaudeService>()
        coEvery { service.sendMessage(any(), any(), any()) } throws SocketTimeoutException()
        val toolBridge = mockk<ToolBridge>()
        coEvery { toolBridge.discoverTools() } returns emptyList()
        coEvery { toolBridge.execute("deleteNote", any()) } returns ToolExecutionResult.Success("true")
        val repository = ChatRepository(service, apiKeyManager(), toolBridge)
        val pending = PendingToolUse("t1", "deleteNote", JsonObject(mapOf("noteId" to JsonPrimitive(5))))
        val history = listOf(
            ClaudeMessage("user", listOf(ClaudeContentBlock.Text("delete note 5"))),
            ClaudeMessage("assistant", listOf(ClaudeContentBlock.ToolUse("t1", "deleteNote", JsonObject(emptyMap())))),
        )

        val result = repository.resolveConfirmation(history, pending, emptyList(), approved = true)

        // The tool already ran; the error history must pair its tool_use with a tool_result so
        // the conversation stays resumable instead of 400ing on every later request.
        assertTrue(result is ChatTurnResult.Error)
        result as ChatTurnResult.Error
        assertEquals(AppError.Timeout, result.error)
        val lastMessage = result.history.last()
        assertEquals("user", lastMessage.role)
        val toolResult = lastMessage.content.single() as ClaudeContentBlock.ToolResult
        assertEquals("t1", toolResult.toolUseId)
    }

    @Test
    fun `send returns Error InvalidApiKey when no key is stored`() = runTest {
        val repository = ChatRepository(mockk<ClaudeService>(), apiKeyManager(null), mockk<ToolBridge>())

        val result = repository.send(listOf(ClaudeMessage("user", listOf(ClaudeContentBlock.Text("hi")))))

        assertTrue(result is ChatTurnResult.Error)
        assertEquals(AppError.InvalidApiKey, (result as ChatTurnResult.Error).error)
    }

    private fun user(text: String) = ClaudeMessage("user", listOf(ClaudeContentBlock.Text(text)))

    private fun noTools(): ToolBridge = mockk<ToolBridge>().also { coEvery { it.discoverTools() } returns emptyList() }

    private fun httpError(code: Int) =
        Response.error<ClaudeResponse>(code, "{}".toResponseBody("application/json".toMediaType()))

    @Test
    fun `send treats an empty stored key as missing and keeps the history`() = runTest {
        val service = mockk<ClaudeService>()
        val history = listOf(user("hi"))

        val result = ChatRepository(service, apiKeyManager(""), mockk()).send(history)

        assertEquals(ChatTurnResult.Error(AppError.InvalidApiKey, history), result)
        coVerify(exactly = 0) { service.sendMessage(any(), any(), any()) }
    }

    @Test
    fun `send builds the request from the stored key, model, history and discovered tools`() = runTest {
        val service = mockk<ClaudeService>()
        val request = slot<ClaudeRequest>()
        coEvery { service.sendMessage("sk-ant-key", CLAUDE_API_VERSION, capture(request)) } returns textResponse("ok")
        val tools = listOf(ClaudeTool("searchNotes", "Search.", JsonObject(emptyMap())))
        val toolBridge = mockk<ToolBridge>()
        coEvery { toolBridge.discoverTools() } returns tools
        val history = listOf(user("hi"))

        ChatRepository(service, apiKeyManager(), toolBridge).send(history)

        assertEquals(
            ClaudeRequest(
                model = CLAUDE_MODEL,
                maxTokens = 2000,
                messages = history,
                tools = tools,
                thinking = ClaudeThinkingConfig("disabled"),
            ),
            request.captured,
        )
    }

    @Test
    fun `send omits the tools field when no tools are discovered`() = runTest {
        val service = mockk<ClaudeService>()
        val request = slot<ClaudeRequest>()
        coEvery { service.sendMessage(any(), any(), capture(request)) } returns textResponse("ok")

        ChatRepository(service, apiKeyManager(), noTools()).send(listOf(user("hi")))

        assertNull(request.captured.tools)
    }

    @Test
    fun `send returns Done with the history extended by the assistant reply`() = runTest {
        val service = mockk<ClaudeService>()
        coEvery { service.sendMessage(any(), any(), any()) } returns textResponse("Hello!")
        val history = listOf(user("hi"))

        val result = ChatRepository(service, apiKeyManager(), noTools()).send(history)

        assertEquals(
            ChatTurnResult.Done(
                history = history + ClaudeMessage("assistant", listOf(ClaudeContentBlock.Text("Hello!"))),
                reply = "Hello!",
                toolCalls = emptyList(),
            ),
            result,
        )
    }

    @Test
    fun `send joins multiple text blocks with newlines`() = runTest {
        val service = mockk<ClaudeService>()
        coEvery { service.sendMessage(any(), any(), any()) } returns Response.success(
            ClaudeResponse(
                content = listOf(ClaudeContentBlock.Text("One"), ClaudeContentBlock.Text("Two")),
                stopReason = "end_turn",
            )
        )

        val result = ChatRepository(service, apiKeyManager(), noTools()).send(listOf(user("hi")))

        assertEquals("One\nTwo", (result as ChatTurnResult.Done).reply)
    }

    @Test
    fun `send maps an HTTP error and keeps the pre-request history`() = runTest {
        val service = mockk<ClaudeService>()
        coEvery { service.sendMessage(any(), any(), any()) } returns httpError(429)
        val history = listOf(user("hi"))

        val result = ChatRepository(service, apiKeyManager(), noTools()).send(history)

        assertEquals(ChatTurnResult.Error(AppError.RateLimited, history), result)
    }

    @Test
    fun `send returns UnknownNetwork when a successful response has no body`() = runTest {
        val service = mockk<ClaudeService>()
        coEvery { service.sendMessage(any(), any(), any()) } returns Response.success<ClaudeResponse>(null)
        val history = listOf(user("hi"))

        val result = ChatRepository(service, apiKeyManager(), noTools()).send(history)

        assertEquals(ChatTurnResult.Error(AppError.UnknownNetwork, history), result)
    }

    @Test
    fun `send does not run tool_use blocks unless the stop reason is tool_use`() = runTest {
        val service = mockk<ClaudeService>()
        coEvery { service.sendMessage(any(), any(), any()) } returns Response.success(
            ClaudeResponse(
                content = listOf(
                    ClaudeContentBlock.Text("Done."),
                    ClaudeContentBlock.ToolUse("t1", "searchNotes", JsonObject(emptyMap())),
                ),
                stopReason = "end_turn",
            )
        )
        val toolBridge = noTools()

        val result = ChatRepository(service, apiKeyManager(), toolBridge).send(listOf(user("hi")))

        assertEquals("Done.", (result as ChatTurnResult.Done).reply)
        assertEquals(emptyList<String>(), result.toolCalls)
        coVerify(exactly = 0) { toolBridge.execute(any(), any()) }
    }

    @Test
    fun `send finishes when the stop reason is tool_use but no tool_use block exists`() = runTest {
        val service = mockk<ClaudeService>()
        coEvery { service.sendMessage(any(), any(), any()) } returns Response.success(
            ClaudeResponse(content = listOf(ClaudeContentBlock.Text("Hm.")), stopReason = "tool_use")
        )

        val result = ChatRepository(service, apiKeyManager(), noTools()).send(listOf(user("hi")))

        assertEquals("Hm.", (result as ChatTurnResult.Done).reply)
        coVerify(exactly = 1) { service.sendMessage(any(), any(), any()) }
    }

    @Test
    fun `send feeds a successful tool result back as a non-error tool_result`() = runTest {
        val service = mockk<ClaudeService>()
        val requests = mutableListOf<ClaudeRequest>()
        coEvery { service.sendMessage(any(), any(), capture(requests)) } returnsMany listOf(
            toolUseResponse("t1", "searchNotes"),
            textResponse("Found it."),
        )
        val toolBridge = noTools()
        coEvery { toolBridge.execute("searchNotes", any()) } returns ToolExecutionResult.Success("[{\"id\":1}]")

        ChatRepository(service, apiKeyManager(), toolBridge).send(listOf(user("find")))

        assertEquals(
            ClaudeMessage("user", listOf(ClaudeContentBlock.ToolResult("t1", "[{\"id\":1}]", isError = false))),
            requests[1].messages.last(),
        )
        assertFalse((requests[1].messages.last().content.single() as ClaudeContentBlock.ToolResult).isError)
    }

    @Test
    fun `send runs the other tools of a destructive turn and holds only the destructive one`() = runTest {
        val service = mockk<ClaudeService>()
        val searchInput = JsonObject(mapOf("query" to JsonPrimitive("milk")))
        val deleteInput = JsonObject(mapOf("noteId" to JsonPrimitive(5)))
        val assistantBlocks = listOf(
            ClaudeContentBlock.ToolUse("t1", "searchNotes", searchInput),
            ClaudeContentBlock.ToolUse("t2", "deleteNote", deleteInput),
        )
        coEvery { service.sendMessage(any(), any(), any()) } returns
            Response.success(ClaudeResponse(content = assistantBlocks, stopReason = "tool_use"))
        val toolBridge = noTools()
        coEvery { toolBridge.execute("searchNotes", searchInput) } returns ToolExecutionResult.Success("[5]")
        val history = listOf(user("find milk and delete it"))

        val result = ChatRepository(service, apiKeyManager(), toolBridge).send(history)

        assertEquals(
            ChatTurnResult.NeedsConfirmation(
                history = history + ClaudeMessage("assistant", assistantBlocks),
                pending = PendingToolUse("t2", "deleteNote", deleteInput),
                resolvedResults = listOf(ClaudeContentBlock.ToolResult("t1", "[5]")),
                toolCalls = listOf("searchNotes", "deleteNote"),
            ),
            result,
        )
        coVerify(exactly = 0) { toolBridge.execute("deleteNote", any()) }
    }

    @Test
    fun `send gives up with an apology after five tool rounds`() = runTest {
        val service = mockk<ClaudeService>()
        coEvery { service.sendMessage(any(), any(), any()) } returns toolUseResponse("t1", "searchNotes")
        val toolBridge = noTools()
        coEvery { toolBridge.execute("searchNotes", any()) } returns ToolExecutionResult.Success("[]")

        val result = ChatRepository(service, apiKeyManager(), toolBridge).send(listOf(user("loop")))

        result as ChatTurnResult.Done
        assertEquals("Sorry, I couldn't resolve that.", result.reply)
        assertEquals(List(5) { "searchNotes" }, result.toolCalls)
        assertEquals(1 + 5 * 2, result.history.size)
        coVerify(exactly = 5) { service.sendMessage(any(), any(), any()) }
    }

    @Test
    fun `resolveConfirmation sends the approved tool's output as its tool_result`() = runTest {
        val service = mockk<ClaudeService>()
        val request = slot<ClaudeRequest>()
        coEvery { service.sendMessage(any(), any(), capture(request)) } returns textResponse("Deleted.")
        val toolBridge = noTools()
        val input = JsonObject(mapOf("noteId" to JsonPrimitive(5)))
        coEvery { toolBridge.execute("deleteNote", input) } returns ToolExecutionResult.Success("true")
        val history = listOf(user("delete 5"))

        val result = ChatRepository(service, apiKeyManager(), toolBridge)
            .resolveConfirmation(history, PendingToolUse("t1", "deleteNote", input), emptyList(), approved = true)

        val toolResultMessage = ClaudeMessage("user", listOf(ClaudeContentBlock.ToolResult("t1", "true")))
        assertEquals(history + toolResultMessage, request.captured.messages)
        assertEquals("Deleted.", (result as ChatTurnResult.Done).reply)
    }
}
