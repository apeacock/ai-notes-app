package com.ai.notes.data.ai

import com.ai.notes.data.model.Note

object BatchSummarizer {
    const val MIN_NOTES = 2
    const val MAX_NOTES = 10

    fun validateSelection(count: Int): AppError? = when {
        count < MIN_NOTES -> AppError.EmptyBatch
        count > MAX_NOTES -> AppError.BatchTooLarge
        else -> null
    }

    fun buildPrompt(notes: List<Note>): String {
        val noteBlocks = notes.joinToString(separator = "\n\n") { note ->
            "${note.title}\n${note.body}"
        }
        return "Summarize the following notes:\n\n$noteBlocks"
    }
}
