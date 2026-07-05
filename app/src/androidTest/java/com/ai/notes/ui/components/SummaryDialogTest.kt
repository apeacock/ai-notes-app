package com.ai.notes.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class SummaryDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaysSummaryText() {
        composeTestRule.setContent {
            SummaryDialog(summary = "This is the Claude-generated summary.", onDismiss = {})
        }
        composeTestRule.onNodeWithText("This is the Claude-generated summary.").assertExists()
    }

    @Test
    fun closeButtonInvokesOnDismiss() {
        var dismissed = false
        composeTestRule.setContent {
            SummaryDialog(summary = "Summary", onDismiss = { dismissed = true })
        }
        composeTestRule.onNodeWithText("Close").performClick()
        assert(dismissed)
    }
}
