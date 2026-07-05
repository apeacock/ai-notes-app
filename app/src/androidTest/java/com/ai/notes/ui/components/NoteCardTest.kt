package com.ai.notes.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.ai.notes.data.model.Note
import org.junit.Rule
import org.junit.Test

class NoteCardTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val sampleNote = Note(
        id = 1,
        title = "Sample Title",
        body = "Sample body preview text that is reasonably long to test truncation",
        tags = listOf("work", "urgent"),
        category = "Personal",
        createdAt = 0L,
        updatedAt = 0L
    )

    @Test
    fun displaysTitleBodyAndTags() {
        composeTestRule.setContent {
            NoteCard(
                note = sampleNote,
                isSelected = false,
                isMultiSelectMode = false,
                onClick = {},
                onLongPress = {},
                onSwipeToDelete = {}
            )
        }

        composeTestRule.onNodeWithText("Sample Title").assertExists()
        composeTestRule.onNodeWithText("#work #urgent", substring = true).assertExists()
    }

    @Test
    fun tapInvokesOnClick() {
        var clicked = false
        composeTestRule.setContent {
            NoteCard(
                note = sampleNote,
                isSelected = false,
                isMultiSelectMode = false,
                onClick = { clicked = true },
                onLongPress = {},
                onSwipeToDelete = {}
            )
        }

        composeTestRule.onNodeWithTag("note_card_1").performClick()
        assert(clicked)
    }

    @Test
    fun longPressInvokesOnLongPress() {
        var longPressed = false
        composeTestRule.setContent {
            NoteCard(
                note = sampleNote,
                isSelected = false,
                isMultiSelectMode = false,
                onClick = {},
                onLongPress = { longPressed = true },
                onSwipeToDelete = {}
            )
        }

        composeTestRule.onNodeWithTag("note_card_1").performTouchInput { longClick() }
        assert(longPressed)
    }

    @Test
    fun swipeLeftInvokesOnSwipeToDelete() {
        var deleted = false
        composeTestRule.setContent {
            NoteCard(
                note = sampleNote,
                isSelected = false,
                isMultiSelectMode = false,
                onClick = {},
                onLongPress = {},
                onSwipeToDelete = { deleted = true }
            )
        }

        composeTestRule.onNodeWithTag("note_card_1").performTouchInput { swipeLeft() }
        assert(deleted)
    }

    @Test
    fun showsCheckmarkWhenSelectedInMultiSelectMode() {
        composeTestRule.setContent {
            NoteCard(
                note = sampleNote,
                isSelected = true,
                isMultiSelectMode = true,
                onClick = {},
                onLongPress = {},
                onSwipeToDelete = {}
            )
        }

        composeTestRule.onNodeWithTag("note_card_checkmark_1").assertExists()
    }
}
