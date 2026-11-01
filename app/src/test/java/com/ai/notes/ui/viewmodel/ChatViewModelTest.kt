package com.ai.notes.ui.viewmodel

import com.ai.notes.data.ai.AppError
import com.ai.notes.data.ai.chat.ChatMessage
import com.ai.notes.data.ai.chat.ChatRepository
import com.ai.notes.data.ai.chat.ChatTurnResult
import com.ai.notes.data.ai.chat.PendingToolUse
import com.ai.notes.data.ai.model.ClaudeContentBlock
import com.ai.notes.data.ai.model.ClaudeMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `sendMessage appends user message then assistant reply on Done`() = runTest {
        val repository = mockk<ChatRepository>()
        coEvery { repository.send(any()) } returns ChatTurnResult.Done(emptyList(), "Hi back!", emptyList())
        val vm = ChatViewModel(repository)

        vm.sendMessage("Hello")
        dispatcher.scheduler.advanceUntilIdle()

        val messages = vm.messages.value
        assertEquals(ChatMessage.FromUser("Hello"), messages[0])
        assertEquals(ChatMessage.FromAssistant("Hi back!"), messages[1])
    }

    @Test
    fun `sendMessage ignores blank input`() = runTest {
        val repository = mockk<ChatRepository>()
        val vm = ChatViewModel(repository)

        vm.sendMessage("   ")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.messages.value.isEmpty())
        coVerify(exactly = 0) { repository.send(any()) }
    }

    @Test
    fun `sendMessage sets errorEvent on Error result`() = runTest {
        val repository = mockk<ChatRepository>()
        coEvery { repository.send(any()) } returns ChatTurnResult.Error(AppError.InvalidApiKey, emptyList())
        val vm = ChatViewModel(repository)

        vm.sendMessage("Hello")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(AppError.InvalidApiKey, vm.errorEvent.value)
    }

    @Test
    fun `sendMessage surfaces tool activity chips before the assistant reply`() = runTest {
        val repository = mockk<ChatRepository>()
        coEvery { repository.send(any()) } returns ChatTurnResult.Done(emptyList(), "Found it.", listOf("searchNotes"))
        val vm = ChatViewModel(repository)

        vm.sendMessage("find my note")
        dispatcher.scheduler.advanceUntilIdle()

        val messages = vm.messages.value
        assertEquals(ChatMessage.ToolActivity("searchNotes"), messages[1])
        assertEquals(ChatMessage.FromAssistant("Found it."), messages[2])
    }

    @Test
    fun `sendMessage sets pendingConfirmation on NeedsConfirmation`() = runTest {
        val repository = mockk<ChatRepository>()
        val pending = PendingToolUse("t1", "deleteNote", JsonObject(mapOf("noteId" to JsonPrimitive(5))))
        val confirmation = ChatTurnResult.NeedsConfirmation(emptyList(), pending, emptyList(), listOf("deleteNote"))
        coEvery { repository.send(any()) } returns confirmation
        val vm = ChatViewModel(repository)

        vm.sendMessage("delete note 5")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(confirmation, vm.pendingConfirmation.value)
    }

    @Test
    fun `confirmPendingAction resolves via repository and clears pendingConfirmation`() = runTest {
        val repository = mockk<ChatRepository>()
        val pending = PendingToolUse("t1", "deleteNote", JsonObject(mapOf("noteId" to JsonPrimitive(5))))
        val confirmation = ChatTurnResult.NeedsConfirmation(emptyList(), pending, emptyList(), listOf("deleteNote"))
        coEvery { repository.send(any()) } returns confirmation
        coEvery { repository.resolveConfirmation(any(), pending, emptyList(), true) } returns
            ChatTurnResult.Done(emptyList(), "Deleted.", emptyList())
        val vm = ChatViewModel(repository)
        vm.sendMessage("delete note 5")
        dispatcher.scheduler.advanceUntilIdle()

        vm.confirmPendingAction()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.pendingConfirmation.value)
        assertTrue(vm.messages.value.any { it == ChatMessage.FromAssistant("Deleted.") })
        coVerify(exactly = 1) { repository.resolveConfirmation(any(), pending, emptyList(), true) }
    }

    @Test
    fun `a failed confirmation round-trip adopts the repository's resolved history`() = runTest {
        val repository = mockk<ChatRepository>()
        val pending = PendingToolUse("t1", "deleteNote", JsonObject(mapOf("noteId" to JsonPrimitive(5))))
        val historyWithToolUse = listOf(
            ClaudeMessage("user", listOf(ClaudeContentBlock.Text("delete note 5"))),
            ClaudeMessage("assistant", listOf(ClaudeContentBlock.ToolUse("t1", "deleteNote", JsonObject(emptyMap())))),
        )
        // The repository already executed the tool and paired it with a tool_result before the
        // follow-up request failed; that resolved history is what the ViewModel must keep.
        val resolvedHistory = historyWithToolUse +
            ClaudeMessage("user", listOf(ClaudeContentBlock.ToolResult(toolUseId = "t1", content = "true")))
        coEvery { repository.send(any()) } returns
            ChatTurnResult.NeedsConfirmation(historyWithToolUse, pending, emptyList(), listOf("deleteNote"))
        coEvery { repository.resolveConfirmation(any(), pending, emptyList(), true) } returns
            ChatTurnResult.Error(AppError.Timeout, resolvedHistory)
        val vm = ChatViewModel(repository)
        vm.sendMessage("delete note 5")
        dispatcher.scheduler.advanceUntilIdle()
        vm.confirmPendingAction()
        dispatcher.scheduler.advanceUntilIdle()

        // The next message must be sent on top of the resolved history, not the one ending in
        // an unanswered tool_use (which the API would reject with a 400).
        val sentHistory = slot<List<ClaudeMessage>>()
        coEvery { repository.send(capture(sentHistory)) } returns ChatTurnResult.Done(emptyList(), "ok", emptyList())
        vm.sendMessage("thanks")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(resolvedHistory, sentHistory.captured.dropLast(1))
        assertEquals(AppError.Timeout, vm.errorEvent.value)
    }

    @Test
    fun `cancelPendingAction resolves with approved false`() = runTest {
        val repository = mockk<ChatRepository>()
        val pending = PendingToolUse("t1", "deleteNote", JsonObject(mapOf("noteId" to JsonPrimitive(5))))
        val confirmation = ChatTurnResult.NeedsConfirmation(emptyList(), pending, emptyList(), listOf("deleteNote"))
        coEvery { repository.send(any()) } returns confirmation
        coEvery { repository.resolveConfirmation(any(), pending, emptyList(), false) } returns
            ChatTurnResult.Done(emptyList(), "Okay, keeping it.", emptyList())
        val vm = ChatViewModel(repository)
        vm.sendMessage("delete note 5")
        dispatcher.scheduler.advanceUntilIdle()

        vm.cancelPendingAction()
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.resolveConfirmation(any(), pending, emptyList(), false) }
    }

    private val pending = PendingToolUse("t1", "deleteNote", JsonObject(mapOf("noteId" to JsonPrimitive(5))))
    private fun msg(role: String, text: String) = ClaudeMessage(role, listOf(ClaudeContentBlock.Text(text)))

    @Test
    fun `sendMessage shows loading until the repository answers`() = runTest {
        val repository = mockk<ChatRepository>()
        val answer = CompletableDeferred<ChatTurnResult>()
        coEvery { repository.send(any()) } coAnswers { answer.await() }
        val vm = ChatViewModel(repository)

        vm.sendMessage("Hello")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.isLoading.value)

        answer.complete(ChatTurnResult.Done(emptyList(), "Hi", emptyList()))
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.isLoading.value)
    }

    @Test
    fun `sendMessage sends the user text as a new wire message`() = runTest {
        val repository = mockk<ChatRepository>()
        val sent = slot<List<ClaudeMessage>>()
        coEvery { repository.send(capture(sent)) } returns ChatTurnResult.Done(emptyList(), "Hi", emptyList())
        val vm = ChatViewModel(repository)

        vm.sendMessage("Hello")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(msg("user", "Hello")), sent.captured)
    }

    @Test
    fun `a Done result's history becomes the base for the next message`() = runTest {
        val repository = mockk<ChatRepository>()
        val doneHistory = listOf(msg("user", "Hello"), msg("assistant", "Hi"))
        val sent = mutableListOf<List<ClaudeMessage>>()
        coEvery { repository.send(capture(sent)) } returns ChatTurnResult.Done(doneHistory, "Hi", emptyList())
        val vm = ChatViewModel(repository)

        vm.sendMessage("Hello")
        dispatcher.scheduler.advanceUntilIdle()
        vm.sendMessage("Again")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(doneHistory + msg("user", "Again"), sent[1])
    }

    @Test
    fun `NeedsConfirmation shows tool activity and confirms against the returned history`() = runTest {
        val repository = mockk<ChatRepository>()
        val confirmHistory = listOf(msg("user", "delete note 5"), msg("assistant", "on it"))
        val resolved = listOf(ClaudeContentBlock.ToolResult("t0", "[5]"))
        coEvery { repository.send(any()) } returns
            ChatTurnResult.NeedsConfirmation(confirmHistory, pending, resolved, listOf("searchNotes", "deleteNote"))
        coEvery { repository.resolveConfirmation(any(), any(), any(), any()) } returns
            ChatTurnResult.Done(emptyList(), "Deleted.", emptyList())
        val vm = ChatViewModel(repository)
        vm.sendMessage("delete note 5")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf(
                ChatMessage.FromUser("delete note 5"),
                ChatMessage.ToolActivity("searchNotes"),
                ChatMessage.ToolActivity("deleteNote"),
            ),
            vm.messages.value,
        )

        vm.confirmPendingAction()
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.resolveConfirmation(confirmHistory, pending, resolved, true) }
    }

    @Test
    fun `confirming with nothing pending does nothing`() = runTest {
        val repository = mockk<ChatRepository>()
        val vm = ChatViewModel(repository)

        vm.confirmPendingAction()
        vm.cancelPendingAction()
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { repository.resolveConfirmation(any(), any(), any(), any()) }
        assertFalse(vm.isLoading.value)
    }

    @Test
    fun `resolving a confirmation shows loading until the repository answers`() = runTest {
        val repository = mockk<ChatRepository>()
        coEvery { repository.send(any()) } returns
            ChatTurnResult.NeedsConfirmation(emptyList(), pending, emptyList(), listOf("deleteNote"))
        val answer = CompletableDeferred<ChatTurnResult>()
        coEvery { repository.resolveConfirmation(any(), any(), any(), any()) } coAnswers { answer.await() }
        val vm = ChatViewModel(repository)
        vm.sendMessage("delete note 5")
        dispatcher.scheduler.advanceUntilIdle()

        vm.confirmPendingAction()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.isLoading.value)
        assertNull(vm.pendingConfirmation.value)

        answer.complete(ChatTurnResult.Done(emptyList(), "Deleted.", emptyList()))
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.isLoading.value)
    }

    @Test
    fun `dismissError clears the error`() = runTest {
        val repository = mockk<ChatRepository>()
        coEvery { repository.send(any()) } returns ChatTurnResult.Error(AppError.Timeout, emptyList())
        val vm = ChatViewModel(repository)
        vm.sendMessage("Hello")
        dispatcher.scheduler.advanceUntilIdle()

        vm.dismissError()

        assertNull(vm.errorEvent.value)
    }
}
