package com.ai.notes.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

class SearchBarTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun typingInvokesOnQueryChanged() {
        var query = ""
        composeTestRule.setContent {
            SearchBar(query = query, onQueryChanged = { query = it }, onClear = {})
        }
        composeTestRule.onNodeWithTag("search_bar_field").performTextInput("pasta")
        assert(query == "pasta")
    }

    @Test
    fun clearButtonInvokesOnClear() {
        var cleared = false
        composeTestRule.setContent {
            SearchBar(query = "existing", onQueryChanged = {}, onClear = { cleared = true })
        }
        composeTestRule.onNodeWithTag("search_bar_clear_button").performClick()
        assert(cleared)
    }

    @Test
    fun displaysCurrentQueryValue() {
        composeTestRule.setContent {
            SearchBar(query = "hello", onQueryChanged = {}, onClear = {})
        }
        composeTestRule.onNodeWithText("hello").assertExists()
    }
}
