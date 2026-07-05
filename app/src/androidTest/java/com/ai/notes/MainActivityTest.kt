package com.ai.notes

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.notes.data.preferences.ApiKeyManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun clearStoredKey() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ApiKeyManager(context).clearApiKey()
    }

    @Test
    fun showsApiKeyPromptWhenNoKeyStored() {
        composeTestRule.onNodeWithText("Enter your Claude API key").assertExists()
    }

    @Test
    fun savingApiKeyTransitionsFromPromptToNotesScreen() {
        composeTestRule.onNodeWithText("Enter your Claude API key").assertExists()

        composeTestRule.onNodeWithTag("api_key_field").performTextInput("test-api-key-1234")
        composeTestRule.onNodeWithText("Save").performClick()

        composeTestRule.onNodeWithText("Enter your Claude API key").assertDoesNotExist()
        composeTestRule.onNodeWithTag("api_key_field").assertDoesNotExist()
        composeTestRule.onNodeWithText("Create").assertExists()
        composeTestRule.onNodeWithText("Search").assertExists()
        composeTestRule.onNodeWithText("Summarize").assertExists()
    }
}
