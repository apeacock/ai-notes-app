package com.ai.notes.ui.theme

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class ThemeSmokeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun themeRendersContent() {
        composeTestRule.setContent {
            AiNotesTheme {
                Text("Themed content")
            }
        }
        composeTestRule.onNodeWithText("Themed content").assertExists()
    }
}
