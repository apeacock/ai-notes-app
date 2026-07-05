package com.ai.notes.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

class NoteEditDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun savingWithEmptyTitleDoesNotInvokeOnSave() {
        var saved = false
        composeTestRule.setContent {
            NoteEditDialog(
                initialNote = null,
                existingCategories = emptyList(),
                onSave = { _, _, _, _ -> saved = true },
                onCancel = {}
            )
        }
        composeTestRule.onNodeWithTag("note_body_field").performTextInput("Some body")
        composeTestRule.onNodeWithText("Save").performClick()
        assert(!saved)
    }

    @Test
    fun savingWithEmptyBodyDoesNotInvokeOnSave() {
        var saved = false
        composeTestRule.setContent {
            NoteEditDialog(
                initialNote = null,
                existingCategories = emptyList(),
                onSave = { _, _, _, _ -> saved = true },
                onCancel = {}
            )
        }
        composeTestRule.onNodeWithTag("note_title_field").performTextInput("Some title")
        composeTestRule.onNodeWithText("Save").performClick()
        assert(!saved)
    }

    @Test
    fun savingWithValidTitleAndBodyInvokesOnSave() {
        var savedTitle: String? = null
        var savedBody: String? = null
        composeTestRule.setContent {
            NoteEditDialog(
                initialNote = null,
                existingCategories = emptyList(),
                onSave = { title, body, _, _ -> savedTitle = title; savedBody = body },
                onCancel = {}
            )
        }
        composeTestRule.onNodeWithTag("note_title_field").performTextInput("Title")
        composeTestRule.onNodeWithTag("note_body_field").performTextInput("Body")
        composeTestRule.onNodeWithText("Save").performClick()
        assert(savedTitle == "Title")
        assert(savedBody == "Body")
    }

    @Test
    fun addingTagChipShowsChipWithRemoveOption() {
        composeTestRule.setContent {
            NoteEditDialog(
                initialNote = null,
                existingCategories = emptyList(),
                onSave = { _, _, _, _ -> },
                onCancel = {}
            )
        }
        composeTestRule.onNodeWithTag("tag_input_field").performTextInput("urgent")
        composeTestRule.onNodeWithTag("tag_add_button").performClick()
        composeTestRule.onNodeWithText("urgent").assertExists()
        composeTestRule.onNodeWithTag("tag_chip_remove_urgent").assertExists()
    }

    @Test
    fun removingTagChipRemovesIt() {
        composeTestRule.setContent {
            NoteEditDialog(
                initialNote = null,
                existingCategories = emptyList(),
                onSave = { _, _, _, _ -> },
                onCancel = {}
            )
        }
        composeTestRule.onNodeWithTag("tag_input_field").performTextInput("urgent")
        composeTestRule.onNodeWithTag("tag_add_button").performClick()
        composeTestRule.onNodeWithTag("tag_chip_remove_urgent").performClick()
        composeTestRule.onNodeWithText("urgent").assertDoesNotExist()
    }

    @Test
    fun pressingEnterInTagInputAddsChip() {
        composeTestRule.setContent {
            NoteEditDialog(
                initialNote = null,
                existingCategories = emptyList(),
                onSave = { _, _, _, _ -> },
                onCancel = {}
            )
        }
        composeTestRule.onNodeWithTag("tag_input_field").performTextInput("urgent")
        composeTestRule.onNodeWithTag("tag_input_field").performImeAction()
        composeTestRule.onNodeWithText("urgent").assertExists()
        composeTestRule.onNodeWithTag("tag_chip_remove_urgent").assertExists()
    }

    @Test
    fun selectingCategorySuggestionUpdatesCategoryField() {
        composeTestRule.setContent {
            NoteEditDialog(
                initialNote = null,
                existingCategories = listOf("Work", "Home"),
                onSave = { _, _, _, _ -> },
                onCancel = {}
            )
        }
        composeTestRule.onNodeWithTag("category_field").performTextInput("Wo")
        composeTestRule.onNodeWithText("Work").assertExists()
        composeTestRule.onNodeWithText("Work").performClick()
        composeTestRule.onNodeWithTag("category_field").assertExists()
        composeTestRule.onNodeWithText("Work").assertExists()
    }

    @Test
    fun cancelInvokesOnCancel() {
        var cancelled = false
        composeTestRule.setContent {
            NoteEditDialog(
                initialNote = null,
                existingCategories = emptyList(),
                onSave = { _, _, _, _ -> },
                onCancel = { cancelled = true }
            )
        }
        composeTestRule.onNodeWithText("Cancel").performClick()
        assert(cancelled)
    }

    @Test
    fun prefillsFieldsWhenEditingExistingNote() {
        val existing = com.ai.notes.data.model.Note(
            id = 1, title = "Existing", body = "Existing body", tags = listOf("a"), category = "Work", createdAt = 0L, updatedAt = 0L
        )
        composeTestRule.setContent {
            NoteEditDialog(
                initialNote = existing,
                existingCategories = listOf("Work", "Home"),
                onSave = { _, _, _, _ -> },
                onCancel = {}
            )
        }
        composeTestRule.onNodeWithText("Existing").assertExists()
        composeTestRule.onNodeWithText("Existing body").assertExists()
    }
}
