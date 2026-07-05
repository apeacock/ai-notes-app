package com.ai.notes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ai.notes.data.ai.RetrofitFactory
import com.ai.notes.data.ai.SummarizationRepository
import com.ai.notes.data.database.NoteDatabase
import com.ai.notes.data.database.repositories.NoteRepository
import com.ai.notes.data.preferences.ApiKeyManager
import com.ai.notes.ui.navigation.AppNavigation
import com.ai.notes.ui.screens.ApiKeyPromptScreen
import com.ai.notes.ui.theme.AiNotesTheme
import com.ai.notes.ui.viewmodel.NotesViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val apiKeyManager = ApiKeyManager(applicationContext)
        val noteRepository = NoteRepository(NoteDatabase.getInstance(applicationContext).noteDao())
        val summarizationRepository = SummarizationRepository(RetrofitFactory.createClaudeService(), apiKeyManager)

        val viewModelFactory = viewModelFactory {
            initializer { NotesViewModel(noteRepository, summarizationRepository) }
        }

        setContent {
            AiNotesTheme {
                Surface {
                    var hasApiKey by remember { mutableStateOf(apiKeyManager.hasApiKey()) }
                    if (hasApiKey) {
                        val viewModel: NotesViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = viewModelFactory)
                        AppNavigation(
                            viewModel = viewModel,
                            onNavigateToApiKeyEdit = {
                                // No dedicated "edit key" route exists yet; reuse the initial
                                // prompt screen to let the user re-enter their API key.
                                hasApiKey = false
                            }
                        )
                    } else {
                        ApiKeyPromptScreen(onSave = { key ->
                            apiKeyManager.saveApiKey(key)
                            hasApiKey = true
                        })
                    }
                }
            }
        }
    }
}
