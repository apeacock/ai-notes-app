package com.ai.notes.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

class ApiKeyPromptScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun savingWithEmptyKeyDoesNotInvokeOnSave() {
        var saved: String? = null
        composeTestRule.setContent {
            ApiKeyPromptScreen(onSave = { saved = it })
        }
        composeTestRule.onNodeWithText("Save").performClick()
        assert(saved == null)
    }

    @Test
    fun savingWithNonEmptyKeyInvokesOnSaveWithValue() {
        var saved: String? = null
        composeTestRule.setContent {
            ApiKeyPromptScreen(onSave = { saved = it })
        }
        composeTestRule.onNodeWithTag("api_key_field").performTextInput("sk-ant-key-123")
        composeTestRule.onNodeWithText("Save").performClick()
        assert(saved == "sk-ant-key-123")
    }

    @Test
    fun showsValidationMessageWhenSavingEmpty() {
        composeTestRule.setContent {
            ApiKeyPromptScreen(onSave = {})
        }
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.onNodeWithText("API key cannot be empty").assertExists()
    }
}
