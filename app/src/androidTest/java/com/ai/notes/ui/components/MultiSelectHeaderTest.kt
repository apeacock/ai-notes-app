package com.ai.notes.ui.components

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

    @Test
    fun summarizeButtonDisabledWhenCanSummarizeFalse() {
        var called = false
        composeTestRule.setContent {
            MultiSelectHeader(selectedCount = 1, canSummarize = false, onSummarize = { called = true }, onCancel = {})
        }
        composeTestRule.onNodeWithText("Summarize").performClick()
        assert(!called)
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
