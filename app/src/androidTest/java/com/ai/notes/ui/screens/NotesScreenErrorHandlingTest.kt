package com.ai.notes.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ai.notes.data.ai.AppError
import com.ai.notes.data.ai.SummarizationRepository
import com.ai.notes.data.ai.SummarizeResult
import com.ai.notes.data.database.repositories.NoteRepository
import com.ai.notes.data.model.Note
import com.ai.notes.ui.viewmodel.NotesViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class NotesScreenErrorHandlingTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun notes() = listOf(
        Note(id = 1, title = "One", body = "Body one", tags = emptyList(), category = null, createdAt = 0L, updatedAt = 0L),
        Note(id = 2, title = "Two", body = "Body two", tags = emptyList(), category = null, createdAt = 0L, updatedAt = 0L)
    )

    private fun buildViewModel(summarizeResult: SummarizeResult): NotesViewModel {
        val noteRepository = mockk<NoteRepository>()
        every { noteRepository.getAllNotes() } returns MutableStateFlow(notes())
        every { noteRepository.searchNotes(any()) } returns MutableStateFlow(notes())
        val summarizationRepository = mockk<SummarizationRepository>()
        coEvery { summarizationRepository.summarize(any()) } returns summarizeResult
        return NotesViewModel(noteRepository, summarizationRepository)
    }

    @Test
    fun invalidApiKeyErrorShowsAlertDialogWithExactMessageAndUpdateKeyButton() {
        val viewModel = buildViewModel(SummarizeResult.Failure(AppError.InvalidApiKey))
        var navigatedToApiKeyEdit = false

        composeTestRule.setContent {
            NotesScreen(viewModel = viewModel, onNavigateToApiKeyEdit = { navigatedToApiKeyEdit = true })
        }

        viewModel.enterMultiSelectMode()
        viewModel.toggleSelection(1)
        viewModel.toggleSelection(2)
        viewModel.summarizeSelected()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Invalid API Key").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithText("Invalid API Key").assertExists()
        composeTestRule.onNodeWithText("Your API key is invalid. Please update it in Settings.").assertExists()
        composeTestRule.onNodeWithText("Update Key").assertExists()

        composeTestRule.onNodeWithText("Update Key").performClick()
        composeTestRule.runOnIdle {
            assert(navigatedToApiKeyEdit) { "Expected onNavigateToApiKeyEdit to be invoked when Update Key is clicked" }
        }
    }

    @Test
    fun emptyBatchErrorMessageStringMatchesSpecExactly() {
        val noteRepository = mockk<NoteRepository>()
        every { noteRepository.getAllNotes() } returns MutableStateFlow(emptyList())
        every { noteRepository.searchNotes(any()) } returns MutableStateFlow(emptyList())
        val viewModel = NotesViewModel(noteRepository, mockk<SummarizationRepository>())
        viewModel.enterMultiSelectMode()
        viewModel.summarizeSelected()
        composeTestRule.setContent {
            NotesScreen(viewModel = viewModel)
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Select at least 2 notes to summarize.").assertExists()
    }
}
