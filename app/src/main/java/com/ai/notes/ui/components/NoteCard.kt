package com.ai.notes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.notes.data.model.Note

private const val SWIPE_DELETE_THRESHOLD_PX = 300f

@Composable
fun NoteCard(
    note: Note,
    isSelected: Boolean,
    isMultiSelectMode: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onSwipeToDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dragOffset by remember { mutableFloatStateOf(0f) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
            .testTag("note_card_${note.id}")
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    dragOffset += delta
                    if (dragOffset < -SWIPE_DELETE_THRESHOLD_PX) {
                        onSwipeToDelete()
                        dragOffset = 0f
                    }
                }
            )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = note.title, style = MaterialTheme.typography.titleMedium)
                if (isMultiSelectMode && isSelected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Selected",
                        modifier = Modifier.testTag("note_card_checkmark_${note.id}")
                    )
                }
            }
            Text(
                text = note.body,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
            if (note.tags.isNotEmpty()) {
                Text(
                    text = note.tags.joinToString(separator = " ") { "#$it" },
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
