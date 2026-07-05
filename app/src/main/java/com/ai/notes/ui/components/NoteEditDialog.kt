package com.ai.notes.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ai.notes.data.model.Note

@Composable
fun NoteEditDialog(
    initialNote: Note?,
    existingCategories: List<String>,
    onSave: (title: String, body: String, tags: List<String>, category: String?) -> Unit,
    onCancel: () -> Unit
) {
    var title by remember { mutableStateOf(initialNote?.title ?: "") }
    var body by remember { mutableStateOf(initialNote?.body ?: "") }
    var tags by remember { mutableStateOf(initialNote?.tags ?: emptyList()) }
    var tagInput by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(initialNote?.category ?: "") }
    var showCategorySuggestions by remember { mutableStateOf(false) }

    fun addTag() {
        val trimmed = tagInput.trim()
        if (trimmed.isNotEmpty() && trimmed !in tags) {
            tags = tags + trimmed
        }
        tagInput = ""
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (initialNote == null) "New Note" else "Edit Note") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("note_title_field")
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Body") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag("note_body_field")
                )
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = tagInput,
                        onValueChange = { tagInput = it },
                        label = { Text("Add tag") },
                        modifier = Modifier.testTag("tag_input_field")
                    )
                    Button(
                        onClick = { addTag() },
                        modifier = Modifier.testTag("tag_add_button")
                    ) {
                        Text("+")
                    }
                }
                LazyRow(modifier = Modifier.padding(top = 8.dp)) {
                    items(tags) { tag ->
                        AssistChip(
                            onClick = {},
                            label = { Text(tag) },
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .testTag("tag_chip_$tag"),
                            trailingIcon = {
                                Text(
                                    text = "x",
                                    modifier = Modifier
                                        .testTag("tag_chip_remove_$tag")
                                        .padding(start = 4.dp)
                                        .clickable { tags = tags - tag }
                                )
                            }
                        )
                    }
                }
                Column {
                    OutlinedTextField(
                        value = category,
                        onValueChange = {
                            category = it
                            showCategorySuggestions = it.isNotEmpty()
                        },
                        label = { Text("Category") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .testTag("category_field")
                    )
                    val suggestions = existingCategories.filter {
                        it.contains(category, ignoreCase = true) && it != category
                    }
                    DropdownMenu(
                        expanded = showCategorySuggestions && suggestions.isNotEmpty(),
                        onDismissRequest = { showCategorySuggestions = false }
                    ) {
                        suggestions.forEach { suggestion ->
                            DropdownMenuItem(
                                text = { Text(suggestion) },
                                onClick = {
                                    category = suggestion
                                    showCategorySuggestions = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (title.isNotBlank() && body.isNotBlank()) {
                    onSave(title, body, tags, category.ifBlank { null })
                }
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    )
}
