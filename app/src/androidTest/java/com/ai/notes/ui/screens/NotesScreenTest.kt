package com.ai.notes.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.ai.notes.data.ai.SummarizationRepository
import com.ai.notes.data.database.repositories.NoteRepository
import com.ai.notes.ui.viewmodel.NotesViewModel
import io.mockk.every
import io.mockk.mockk
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
}
