package com.ai.notes.ui.components

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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

    // 0 selected: below the minimum. The Summarize button must be disabled.
    //
    // Note on Compose testing: performClick() invokes the semantics OnClick action directly;
    // it is not a real touch dispatch, so it does NOT respect Modifier.clickable(enabled = false)
    // the way an actual tap would -- the disabled() semantics marker is separate from the
    // OnClick action, and performClick() ignores it. That means clicking a "disabled" button in
    // a test still fires onSummarize(). assertIsNotEnabled() is therefore the primary, sound
    // assertion for disabled state here; the click+snackbar check is kept only as a secondary
    // confirmation that the validation message shown once the click *does* fire is correct.
    @Test
    fun summarizeDisabledWithZeroSelected() {
        val viewModel = buildViewModel(3)
        viewModel.enterMultiSelectMode()
        composeTestRule.setContent { NotesScreen(viewModel = viewModel) }

        composeTestRule.onNodeWithText("Summarize").assertIsNotEnabled()

        composeTestRule.onNodeWithText("Summarize").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Select at least 2 notes to summarize.").assertExists()
    }

    // 1 selected: still below the minimum of 2. Same reasoning as the zero-selected case above.
    @Test
    fun summarizeDisabledWithOneSelected() {
        val viewModel = buildViewModel(3)
        viewModel.enterMultiSelectMode()
        viewModel.toggleSelection(1)
        composeTestRule.setContent { NotesScreen(viewModel = viewModel) }

        composeTestRule.onNodeWithText("Summarize").assertIsNotEnabled()

        composeTestRule.onNodeWithText("Summarize").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Select at least 2 notes to summarize.").assertExists()
    }

    // 2 selected: the minimum valid boundary. Button must be enabled.
    @Test
    fun summarizeEnabledWithTwoSelected() {
        val viewModel = buildViewModel(5)
        viewModel.enterMultiSelectMode()
        viewModel.toggleSelection(1)
        viewModel.toggleSelection(2)
        composeTestRule.setContent { NotesScreen(viewModel = viewModel) }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("2 selected").assertExists()
        composeTestRule.onNodeWithText("Summarize").assertIsEnabled()
    }

    // 10 selected: the maximum valid boundary. Button must still be enabled.
    @Test
    fun summarizeEnabledWithTenSelected() {
        val viewModel = buildViewModel(10)
        viewModel.enterMultiSelectMode()
        (1..10).forEach { viewModel.toggleSelection(it) }
        composeTestRule.setContent { NotesScreen(viewModel = viewModel) }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("10 selected").assertExists()
        composeTestRule.onNodeWithText("Summarize").assertIsEnabled()
    }

    // 11 selected: above the maximum. Button must be disabled.
    // As above, performClick() still fires onSummarize() despite the disabled semantics flag,
    // so the snackbar assertion is a secondary confirmation only -- assertIsNotEnabled() is the
    // primary, sound proof that the button is disabled at this boundary.
    @Test
    fun summarizeDisabledWhenMoreThanTenSelected() {
        val viewModel = buildViewModel(11)
        viewModel.enterMultiSelectMode()
        (1..11).forEach { viewModel.toggleSelection(it) }
        composeTestRule.setContent { NotesScreen(viewModel = viewModel) }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("11 selected").assertExists()
        composeTestRule.onNodeWithText("Summarize").assertIsNotEnabled()

        composeTestRule.onNodeWithText("Summarize").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Maximum 10 notes allowed per summary. Deselect some notes.").assertExists()
    }
}
