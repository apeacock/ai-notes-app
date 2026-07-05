package com.ai.notes.data.ai

sealed class AppError(val userMessage: String) {
    object NoInternet : AppError("No internet connection. Please check your network settings.")
    object RateLimited : AppError("Too many requests. Please wait a moment and try again.")
    object Timeout : AppError("Request timed out. Please try again.")
    object UnknownNetwork : AppError("Network error. Please try again.")
    object InvalidApiKey : AppError("Your API key is invalid. Please update it in Settings.")
    object InvalidRequest : AppError("Invalid request. Please check your notes and try again.")
    object ServerError : AppError("Service unavailable. Please try again later.")
    data class DatabaseError(val detail: String) : AppError("Database error. Please report this issue.")
    object EmptyBatch : AppError("Select at least 2 notes to summarize.")
    object BatchTooLarge : AppError("Maximum 10 notes allowed per summary. Deselect some notes.")
}
