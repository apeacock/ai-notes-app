package com.ai.notes.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.notes.data.ai.AppError
import com.ai.notes.data.ai.chat.ChatMessage
import com.ai.notes.data.ai.chat.ChatRepository
import com.ai.notes.data.ai.chat.ChatTurnResult
import com.ai.notes.data.ai.model.ClaudeContentBlock
import com.ai.notes.data.ai.model.ClaudeMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(private val chatRepository: ChatRepository) : ViewModel() {

    private var wireHistory: List<ClaudeMessage> = emptyList()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<ChatTurnResult.NeedsConfirmation?>(null)
    val pendingConfirmation: StateFlow<ChatTurnResult.NeedsConfirmation?> = _pendingConfirmation.asStateFlow()

    private val _errorEvent = MutableStateFlow<AppError?>(null)
    val errorEvent: StateFlow<AppError?> = _errorEvent.asStateFlow()

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        _messages.value = _messages.value + ChatMessage.FromUser(text)
        wireHistory = wireHistory + ClaudeMessage(role = "user", content = listOf(ClaudeContentBlock.Text(text)))
        viewModelScope.launch {
            _isLoading.value = true
            try {
                applyResult(chatRepository.send(wireHistory))
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun confirmPendingAction() = resolvePending(approved = true)

    fun cancelPendingAction() = resolvePending(approved = false)

    fun dismissError() {
        _errorEvent.value = null
    }

    private fun resolvePending(approved: Boolean) {
        val confirmation = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = null
        viewModelScope.launch {
            _isLoading.value = true
            try {
                applyResult(
                    chatRepository.resolveConfirmation(
                        confirmation.history,
                        confirmation.pending,
                        confirmation.resolvedResults,
                        approved,
                    )
                )
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun applyResult(result: ChatTurnResult) {
        when (result) {
            is ChatTurnResult.Done -> {
                wireHistory = result.history
                _messages.value = _messages.value +
                    result.toolCalls.map { ChatMessage.ToolActivity(it) } +
                    ChatMessage.FromAssistant(result.reply)
            }
            is ChatTurnResult.NeedsConfirmation -> {
                wireHistory = result.history
                _messages.value = _messages.value + result.toolCalls.map { ChatMessage.ToolActivity(it) }
                _pendingConfirmation.value = result
            }
            is ChatTurnResult.Error -> {
                // Adopt the repository's last valid history. After a confirmed tool has already
                // executed, the old wireHistory ends with an unanswered tool_use block; keeping
                // it would make every subsequent request fail with a 400.
                wireHistory = result.history
                _errorEvent.value = result.error
            }
        }
    }
}
