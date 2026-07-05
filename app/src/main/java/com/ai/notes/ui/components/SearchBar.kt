package com.ai.notes.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun SearchBar(query: String, onQueryChanged: (String) -> Unit, onClear: () -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = Modifier.fillMaxWidth().padding(8.dp).testTag("search_bar_field"),
        trailingIcon = {
            IconButton(onClick = onClear, modifier = Modifier.testTag("search_bar_clear_button")) {
                Icon(Icons.Filled.Clear, contentDescription = "Clear search")
            }
        }
    )
}
