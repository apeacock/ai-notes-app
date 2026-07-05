package com.ai.notes.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ai.notes.ui.screens.ChatScreen
import com.ai.notes.ui.screens.NotesScreen
import com.ai.notes.ui.viewmodel.ChatViewModel
import com.ai.notes.ui.viewmodel.NotesViewModel

const val NOTES_ROUTE = "notes"
const val CHAT_ROUTE = "chat"

@Composable
fun AppNavigation(
    viewModel: NotesViewModel,
    chatViewModel: ChatViewModel,
    onNavigateToApiKeyEdit: () -> Unit = {}
) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = NOTES_ROUTE) {
        composable(NOTES_ROUTE) {
            NotesScreen(
                viewModel = viewModel,
                onNavigateToApiKeyEdit = onNavigateToApiKeyEdit,
                onNavigateToChat = { navController.navigate(CHAT_ROUTE) }
            )
        }
        composable(CHAT_ROUTE) {
            ChatScreen(viewModel = chatViewModel, onBack = { navController.popBackStack() })
        }
    }
}
