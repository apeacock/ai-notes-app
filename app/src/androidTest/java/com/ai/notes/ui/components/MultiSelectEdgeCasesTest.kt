package com.ai.notes.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ai.notes.data.ai.SummarizationRepository
import com.ai.notes.data.database.repositories.NoteRepository
import com.ai.notes.data.model.Note
import com.ai.notes.ui.screens.NotesScreen
import com.ai.notes.ui.viewmodel.NotesViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class MultiSelectEdgeCasesTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun note(n: Int) = Note(n, "Title $n", "Body $n", emptyList(), null, 0L, 0L)

    private fun buildViewModel(count: Int): NotesViewModel {
        val notes = (1..count).map { note(it) }
        val noteRepository = mockk<NoteRepository>()
        every { noteRepository.getAllNotes() } returns MutableStateFlow(notes)
        every { noteRepository.searchNotes(any()) } returns MutableStateFlow(notes)
        return NotesViewModel(noteRepository, mockk<SummarizationRepository>())
    }

    @Test
    fun summarizeDisabledWithZeroSelected() {
        val viewModel = buildViewModel(3)
        viewModel.enterMultiSelectMode()
        composeTestRule.setContent { NotesScreen(viewModel = viewModel) }

        composeTestRule.onNodeWithText("Summarize").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Select at least 2 notes to summarize.").assertExists()
    }

    @Test
    fun summarizeShowsSnackbarWithOneSelected() {
        val viewModel = buildViewModel(3)
        viewModel.enterMultiSelectMode()
        viewModel.toggleSelection(1)
        composeTestRule.setContent { NotesScreen(viewModel = viewModel) }

        composeTestRule.onNodeWithText("Summarize").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Select at least 2 notes to summarize.").assertExists()
    }

    @Test
    fun summarizeButtonDisabledWhenMoreThanTenSelected() {
        val viewModel = buildViewModel(11)
        viewModel.enterMultiSelectMode()
        (1..11).forEach { viewModel.toggleSelection(it) }
        composeTestRule.setContent { NotesScreen(viewModel = viewModel) }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("11 selected").assertExists()
        // The Summarize button exists but is disabled; clicking it must not invoke the repository.
        composeTestRule.onNodeWithText("Summarize").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Maximum 10 notes allowed per summary. Deselect some notes.").assertDoesNotExist()
    }

    @Test
    fun summarizeEnabledBetweenTwoAndTenSelected() {
        val viewModel = buildViewModel(5)
        viewModel.enterMultiSelectMode()
        viewModel.toggleSelection(1)
        viewModel.toggleSelection(2)
        composeTestRule.setContent { NotesScreen(viewModel = viewModel) }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("2 selected").assertExists()
    }
}
