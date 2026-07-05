package com.ai.notes.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.notes.data.ai.AppError
import com.ai.notes.data.ai.BatchSummarizer
import com.ai.notes.data.ai.SummarizationRepository
import com.ai.notes.data.ai.SummarizeResult
import com.ai.notes.data.database.repositories.NoteRepository
import com.ai.notes.data.model.Note
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NotesViewModel(
    private val noteRepository: NoteRepository,
    private val summarizationRepository: SummarizationRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val notes: StateFlow<List<Note>> = _searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) noteRepository.getAllNotes() else noteRepository.searchNotes(query)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedIds = MutableStateFlow<Set<Int>>(emptySet())
    val selectedIds: StateFlow<Set<Int>> = _selectedIds.asStateFlow()

    private val _isMultiSelectMode = MutableStateFlow(false)
    val isMultiSelectMode: StateFlow<Boolean> = _isMultiSelectMode.asStateFlow()

    private val _summary = MutableStateFlow<String?>(null)
    val summary: StateFlow<String?> = _summary.asStateFlow()

    private val _errorEvent = MutableStateFlow<AppError?>(null)
    val errorEvent: StateFlow<AppError?> = _errorEvent.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun enterMultiSelectMode() {
        _isMultiSelectMode.value = true
    }

    fun exitMultiSelectMode() {
        _isMultiSelectMode.value = false
        _selectedIds.value = emptySet()
    }

    fun toggleSelection(noteId: Int) {
        _selectedIds.value = if (noteId in _selectedIds.value) {
            _selectedIds.value - noteId
        } else {
            _selectedIds.value + noteId
        }
    }

    fun canSummarize(): Boolean = BatchSummarizer.validateSelection(_selectedIds.value.size) == null

    fun summarizeSelected() {
        val validationError = BatchSummarizer.validateSelection(_selectedIds.value.size)
        if (validationError != null) {
            _errorEvent.value = validationError
            return
        }

        // Fetch the current note list directly from the repository (rather than reading
        // notes.value) so this doesn't silently depend on some external collector already
        // having subscribed to the lazily-shared `notes` StateFlow (WhileSubscribed(5000)
        // only starts collecting once something observes it, e.g. Compose's collectAsState).
        val selectedIdsSnapshot = _selectedIds.value
        val query = _searchQuery.value
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val currentNotes = if (query.isBlank()) {
                    noteRepository.getAllNotes().first()
                } else {
                    noteRepository.searchNotes(query).first()
                }
                val selectedNotes = currentNotes.filter { it.id in selectedIdsSnapshot }
                when (val result = summarizationRepository.summarize(selectedNotes)) {
                    is SummarizeResult.Success -> _summary.value = result.summary
                    is SummarizeResult.Failure -> _errorEvent.value = result.error
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun dismissSummary() {
        _summary.value = null
    }

    fun dismissError() {
        _errorEvent.value = null
    }

    fun createNote(title: String, body: String, tags: List<String>, category: String?) {
        viewModelScope.launch {
            noteRepository.createNote(title, body, tags, category)
        }
    }

    fun updateNote(note: Note) {
        viewModelScope.launch {
            noteRepository.updateNote(note)
        }
    }

    fun deleteNote(id: Int) {
        viewModelScope.launch {
            noteRepository.deleteNote(id)
        }
    }
}
