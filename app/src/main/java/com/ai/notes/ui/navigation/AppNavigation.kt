package com.ai.notes.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ai.notes.ui.screens.NotesScreen
import com.ai.notes.ui.viewmodel.NotesViewModel

const val NOTES_ROUTE = "notes"

@Composable
fun AppNavigation(
    viewModel: NotesViewModel,
    onNavigateToApiKeyEdit: () -> Unit = {}
) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = NOTES_ROUTE) {
        composable(NOTES_ROUTE) {
            NotesScreen(viewModel = viewModel, onNavigateToApiKeyEdit = onNavigateToApiKeyEdit)
        }
    }
}
