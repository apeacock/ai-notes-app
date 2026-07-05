package com.ai.notes.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ai.notes.data.ai.SummarizationRepository
import com.ai.notes.data.ai.SummarizeResult
import com.ai.notes.data.database.repositories.NoteRepository
import com.ai.notes.data.model.Note
import com.ai.notes.ui.viewmodel.NotesViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class NotesScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun buildViewModel(): NotesViewModel {
        val noteRepository = mockk<NoteRepository>()
        every { noteRepository.getAllNotes() } returns MutableStateFlow(emptyList())
        every { noteRepository.searchNotes(any()) } returns MutableStateFlow(emptyList())
        val summarizationRepository = mockk<SummarizationRepository>()
        return NotesViewModel(noteRepository, summarizationRepository)
    }

    @Test
    fun showsEmptyStateWhenNoNotes() {
        composeTestRule.setContent {
            NotesScreen(viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithText("No notes yet").assertExists()
    }

    @Test
    fun showsBottomNavigationItems() {
        composeTestRule.setContent {
            NotesScreen(viewModel = buildViewModel())
        }

        composeTestRule.onNodeWithText("Create").assertExists()
        composeTestRule.onNodeWithText("Search").assertExists()
        composeTestRule.onNodeWithText("Summarize").assertExists()
    }

    @Test
    fun tappingNoteInNonMultiSelectModeOpensEditDialogPrefilled() {
        val notes = listOf(
            Note(id = 1, title = "Groceries", body = "Milk and eggs", tags = listOf("home"), category = "Personal", createdAt = 0L, updatedAt = 0L)
        )
        val noteRepository = mockk<NoteRepository>()
        every { noteRepository.getAllNotes() } returns MutableStateFlow(notes)
        every { noteRepository.searchNotes(any()) } returns MutableStateFlow(notes)
        val summarizationRepository = mockk<SummarizationRepository>()
        val viewModel = NotesViewModel(noteRepository, summarizationRepository)

        composeTestRule.setContent {
            NotesScreen(viewModel = viewModel)
        }

        composeTestRule.onNodeWithTag("note_card_1").performClick()

        composeTestRule.onNodeWithText("Edit Note").assertExists()
        composeTestRule.onNodeWithTag("note_title_field").assertTextContains("Groceries")
        composeTestRule.onNodeWithTag("note_body_field").assertTextContains("Milk and eggs")
    }

    @Test
    fun showsLoadingIndicatorWhileSummarizing() {
        val notes = listOf(
            Note(id = 1, title = "One", body = "Body one", tags = emptyList(), category = null, createdAt = 0L, updatedAt = 0L),
            Note(id = 2, title = "Two", body = "Body two", tags = emptyList(), category = null, createdAt = 0L, updatedAt = 0L)
        )
        val noteRepository = mockk<NoteRepository>()
        every { noteRepository.getAllNotes() } returns MutableStateFlow(notes)
        every { noteRepository.searchNotes(any()) } returns MutableStateFlow(notes)
        val summarizationRepository = mockk<SummarizationRepository>()
        // Never resolves, so isLoading stays true for the duration of this test —
        // we only need to observe the indicator appearing, not the eventual result.
        coEvery { summarizationRepository.summarize(any()) } coAnswers { awaitCancellation() }
        val viewModel = NotesViewModel(noteRepository, summarizationRepository)

        composeTestRule.setContent {
            NotesScreen(viewModel = viewModel)
        }

        viewModel.enterMultiSelectMode()
        viewModel.toggleSelection(1)
        viewModel.toggleSelection(2)
        viewModel.summarizeSelected()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("loading_indicator").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("loading_indicator").assertExists()
    }

    @Test
    fun tappingChatIconInvokesOnNavigateToChat() {
        var navigatedToChat = false
        composeTestRule.setContent {
            NotesScreen(viewModel = buildViewModel(), onNavigateToChat = { navigatedToChat = true })
        }

        composeTestRule.onNodeWithTag("chat_nav_icon").performClick()

        composeTestRule.runOnIdle {
            assert(navigatedToChat) { "Expected onNavigateToChat to be invoked when the chat icon is tapped" }
        }
    }
}
