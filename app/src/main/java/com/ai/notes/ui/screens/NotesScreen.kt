package com.ai.notes.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.ai.notes.data.ai.AppError
import com.ai.notes.ui.components.NoteCard
import com.ai.notes.ui.viewmodel.NotesViewModel

@Composable
fun NotesScreen(
    viewModel: NotesViewModel,
    onNavigateToApiKeyEdit: () -> Unit = {}
) {
    val notes by viewModel.notes.collectAsState()
    val isMultiSelectMode by viewModel.isMultiSelectMode.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val summary by viewModel.summary.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var showSearchBar by remember { mutableStateOf(false) }
    val errorEvent by viewModel.errorEvent.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(errorEvent) {
        val error = errorEvent ?: return@LaunchedEffect
        // Critical errors (InvalidApiKey, DatabaseError) are rendered via AlertDialog below;
        // every other AppError subtype is shown as a Snackbar here. This isn't an exhaustive
        // `when` over AppError, so a newly added subtype defaults to Snackbar unless it's
        // explicitly added to the critical branch of the `when` further down in this function.
        val isCritical = error is AppError.InvalidApiKey || error is AppError.DatabaseError
        if (!isCritical) {
            snackbarHostState.showSnackbar(error.userMessage)
            viewModel.dismissError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = false,
                    onClick = { showCreateDialog = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = "Create") },
                    label = { Text("Create") }
                )
                NavigationBarItem(
                    selected = showSearchBar,
                    onClick = { showSearchBar = !showSearchBar },
                    icon = { Icon(Icons.Filled.Search, contentDescription = "Search") },
                    label = { Text("Search") }
                )
                NavigationBarItem(
                    selected = isMultiSelectMode,
                    onClick = {
                        if (isMultiSelectMode) viewModel.exitMultiSelectMode() else viewModel.enterMultiSelectMode()
                    },
                    icon = { Icon(Icons.Filled.Description, contentDescription = "Summarize") },
                    label = { Text("Summarize") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (showSearchBar) {
                    com.ai.notes.ui.components.SearchBar(
                        query = searchQuery,
                        onQueryChanged = { viewModel.onSearchQueryChanged(it) },
                        onClear = { viewModel.onSearchQueryChanged("") }
                    )
                }
                if (isMultiSelectMode) {
                    com.ai.notes.ui.components.MultiSelectHeader(
                        selectedCount = selectedIds.size,
                        canSummarize = viewModel.canSummarize(),
                        onSummarize = { viewModel.summarizeSelected() },
                        onCancel = { viewModel.exitMultiSelectMode() }
                    )
                }
                if (notes.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("No notes yet", style = MaterialTheme.typography.titleMedium)
                        Button(onClick = { showCreateDialog = true }) {
                            Text("Create a note")
                        }
                    }
                } else {
                    LazyColumn {
                        items(notes, key = { it.id }) { note ->
                            NoteCard(
                                note = note,
                                isSelected = note.id in selectedIds,
                                isMultiSelectMode = isMultiSelectMode,
                                onClick = { if (isMultiSelectMode) viewModel.toggleSelection(note.id) },
                                onLongPress = {
                                    if (!isMultiSelectMode) viewModel.enterMultiSelectMode()
                                    viewModel.toggleSelection(note.id)
                                },
                                onSwipeToDelete = { viewModel.deleteNote(note.id) }
                            )
                        }
                    }
                }
            }
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .testTag("loading_indicator")
                )
            }
        }
    }

    if (showCreateDialog) {
        com.ai.notes.ui.components.NoteEditDialog(
            initialNote = null,
            existingCategories = notes.mapNotNull { it.category }.distinct(),
            onSave = { title, body, tags, category ->
                viewModel.createNote(title, body, tags, category)
                showCreateDialog = false
            },
            onCancel = { showCreateDialog = false }
        )
    }

    summary?.let { summaryText ->
        com.ai.notes.ui.components.SummaryDialog(
            summary = summaryText,
            onDismiss = { viewModel.dismissSummary() }
        )
    }

    when (val criticalError = errorEvent) {
        is AppError.InvalidApiKey -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissError() },
                title = { Text("Invalid API Key") },
                text = { Text(criticalError.userMessage) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.dismissError()
                        onNavigateToApiKeyEdit()
                    }) {
                        Text("Update Key")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissError() }) {
                        Text("Cancel")
                    }
                }
            )
        }
        is AppError.DatabaseError -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissError() },
                title = { Text("Database Error") },
                text = { Text(criticalError.userMessage) },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissError() }) {
                        Text("OK")
                    }
                }
            )
        }
        else -> Unit
    }
}
