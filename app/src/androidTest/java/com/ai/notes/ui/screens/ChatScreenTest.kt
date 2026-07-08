package com.ai.notes.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.ai.notes.data.ai.AppError
import com.ai.notes.data.ai.chat.ChatRepository
import com.ai.notes.data.ai.chat.ChatTurnResult
import com.ai.notes.data.ai.chat.PendingToolUse
import com.ai.notes.ui.viewmodel.ChatViewModel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test

class ChatScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun sendingMessageShowsUserBubbleAndAssistantReply() {
        val chatRepository = mockk<ChatRepository>()
        coEvery { chatRepository.send(any()) } returns ChatTurnResult.Done(emptyList(), "Hello there!", emptyList())
        val viewModel = ChatViewModel(chatRepository)

        composeTestRule.setContent { ChatScreen(viewModel = viewModel) }

        composeTestRule.onNodeWithTag("chat_input_field").performTextInput("Hi")
        composeTestRule.onNodeWithTag("chat_send_button").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Hello there!").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Hi").assertExists()
        composeTestRule.onNodeWithText("Hello there!").assertExists()
    }

    @Test
    fun deleteConfirmationDialogAppearsAndConfirmingInvokesRepository() {
        val chatRepository = mockk<ChatRepository>()
        val pending = PendingToolUse(
            toolUseId = "tool_1",
            functionName = "deleteNote",
            input = JsonObject(mapOf("noteId" to JsonPrimitive(5))),
        )
        coEvery { chatRepository.send(any()) } returns ChatTurnResult.NeedsConfirmation(
            history = emptyList(),
            pending = pending,
            resolvedResults = emptyList(),
            toolCalls = listOf("deleteNote"),
        )
        coEvery { chatRepository.resolveConfirmation(any(), pending, emptyList(), true) } returns
            ChatTurnResult.Done(emptyList(), "Deleted it.", emptyList())
        val viewModel = ChatViewModel(chatRepository)

        composeTestRule.setContent { ChatScreen(viewModel = viewModel) }

        composeTestRule.onNodeWithTag("chat_input_field").performTextInput("delete note 5")
        composeTestRule.onNodeWithTag("chat_send_button").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Delete note #5? This can't be undone.").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Delete").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Deleted it.").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun cancellingDeleteConfirmationDoesNotExecute() {
        val chatRepository = mockk<ChatRepository>()
        val pending = PendingToolUse(
            toolUseId = "tool_1",
            functionName = "deleteNote",
            input = JsonObject(mapOf("noteId" to JsonPrimitive(5))),
        )
        coEvery { chatRepository.send(any()) } returns ChatTurnResult.NeedsConfirmation(
            history = emptyList(),
            pending = pending,
            resolvedResults = emptyList(),
            toolCalls = listOf("deleteNote"),
        )
        coEvery { chatRepository.resolveConfirmation(any(), pending, emptyList(), false) } returns
            ChatTurnResult.Done(emptyList(), "Okay, keeping it.", emptyList())
        val viewModel = ChatViewModel(chatRepository)

        composeTestRule.setContent { ChatScreen(viewModel = viewModel) }

        composeTestRule.onNodeWithTag("chat_input_field").performTextInput("delete note 5")
        composeTestRule.onNodeWithTag("chat_send_button").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Cancel").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Cancel").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Okay, keeping it.").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun errorFromRepositoryShowsSnackbarWithUserMessage() {
        val chatRepository = mockk<ChatRepository>()
        coEvery { chatRepository.send(any()) } returns ChatTurnResult.Error(AppError.InvalidApiKey, emptyList())
        val viewModel = ChatViewModel(chatRepository)

        composeTestRule.setContent { ChatScreen(viewModel = viewModel) }

        composeTestRule.onNodeWithTag("chat_input_field").performTextInput("Hi")
        composeTestRule.onNodeWithTag("chat_send_button").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(AppError.InvalidApiKey.userMessage).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(AppError.InvalidApiKey.userMessage).assertExists()
    }
}
