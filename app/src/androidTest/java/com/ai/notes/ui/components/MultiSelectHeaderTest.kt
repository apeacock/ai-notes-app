package com.ai.notes.ui.components

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class MultiSelectHeaderTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaysSelectionCount() {
        composeTestRule.setContent {
            MultiSelectHeader(selectedCount = 3, canSummarize = true, onSummarize = {}, onCancel = {})
        }
        composeTestRule.onNodeWithText("3 selected").assertExists()
    }

    // Note on Compose testing: performClick() invokes the semantics OnClick action directly;
    // it is not a real touch dispatch, so it does NOT respect Modifier.clickable(enabled = false)
    // the way an actual tap would -- performClick() would still fire onSummarize() even though
    // the button is visually/semantically disabled. assertIsNotEnabled() is therefore the sound
    // assertion for disabled state here (see MultiSelectEdgeCasesTest for the same pattern).
    @Test
    fun summarizeButtonDisabledWhenCanSummarizeFalse() {
        composeTestRule.setContent {
            MultiSelectHeader(selectedCount = 1, canSummarize = false, onSummarize = {}, onCancel = {})
        }
        composeTestRule.onNodeWithText("Summarize").assertIsNotEnabled()
    }

    @Test
    fun summarizeButtonEnabledInvokesCallback() {
        var called = false
        composeTestRule.setContent {
            MultiSelectHeader(selectedCount = 2, canSummarize = true, onSummarize = { called = true }, onCancel = {})
        }
        composeTestRule.onNodeWithText("Summarize").performClick()
        assert(called)
    }

    @Test
    fun cancelButtonInvokesCallback() {
        var cancelled = false
        composeTestRule.setContent {
            MultiSelectHeader(selectedCount = 2, canSummarize = true, onSummarize = {}, onCancel = { cancelled = true })
        }
        composeTestRule.onNodeWithText("Cancel").performClick()
        assert(cancelled)
    }
}
