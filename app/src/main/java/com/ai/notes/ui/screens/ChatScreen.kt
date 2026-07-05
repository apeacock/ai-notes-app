package com.ai.notes.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ai.notes.data.ai.chat.ChatMessage
import com.ai.notes.ui.viewmodel.ChatViewModel
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel, onBack: () -> Unit = {}) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsState()
    var inputText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxSize()) {
                items(messages) { message ->
                    when (message) {
                        is ChatMessage.FromUser -> Text(
                            text = message.text,
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            textAlign = TextAlign.End,
                        )
                        is ChatMessage.FromAssistant -> Text(
                            text = message.text,
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                        )
                        is ChatMessage.ToolActivity -> Text(
                            text = "Called ${message.functionName}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        )
                    }
                }
            }
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .testTag("chat_loading_indicator")
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f).testTag("chat_input_field"),
                    placeholder = { Text("Ask something...") },
                )
                IconButton(
                    onClick = {
                        viewModel.sendMessage(inputText)
                        inputText = ""
                    },
                    enabled = !isLoading && inputText.isNotBlank(),
                    modifier = Modifier.testTag("chat_send_button"),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }

    pendingConfirmation?.let { confirmation ->
        val noteId = confirmation.pending.input["noteId"]?.jsonPrimitive?.intOrNull
        AlertDialog(
            onDismissRequest = { viewModel.cancelPendingAction() },
            title = { Text("Delete note?") },
            text = {
                Text(
                    if (noteId != null) "Delete note #$noteId? This can't be undone."
                    else "Delete this note? This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmPendingAction() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelPendingAction() }) { Text("Cancel") }
            }
        )
    }
}
