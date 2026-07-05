package com.ai.notes.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.ai.notes.data.model.Note
import org.junit.Rule
import org.junit.Test

class NoteCardHapticsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val sampleNote = Note(
        id = 1, title = "T", body = "B", tags = emptyList(), category = null, createdAt = 0L, updatedAt = 0L
    )

    @Test
    fun longPressStillInvokesCallbackAfterHapticsAdded() {
        var longPressed = false
        composeTestRule.setContent {
            NoteCard(sampleNote, false, false, {}, { longPressed = true }, {})
        }
        composeTestRule.onNodeWithTag("note_card_1").performTouchInput { longClick() }
        assert(longPressed)
    }

    @Test
    fun swipeStillInvokesCallbackAfterHapticsAdded() {
        var deleted = false
        composeTestRule.setContent {
            NoteCard(sampleNote, false, false, {}, {}, { deleted = true })
        }
        composeTestRule.onNodeWithTag("note_card_1").performTouchInput { swipeLeft() }
        assert(deleted)
    }
}
