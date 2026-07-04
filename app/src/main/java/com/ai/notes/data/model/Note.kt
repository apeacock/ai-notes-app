package com.ai.notes.data.model

data class Note(
    val id: Int = 0,
    val title: String,
    val body: String,
    val tags: List<String>,
    val category: String?,
    val createdAt: Long,
    val updatedAt: Long
)
