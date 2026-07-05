package com.ai.notes.data.model

import androidx.appfunctions.AppFunctionSerializable

/**
 * Annotated with [AppFunctionSerializable] so this type can be used as a
 * parameter/return type of [com.ai.notes.AppFunctions.NoteFunctions]'
 * `@AppFunction`-annotated methods; the AppFunctions KSP compiler generates
 * (de)serialization code for it based on this annotation.
 */
@AppFunctionSerializable
data class Note(
    val id: Int = 0,
    val title: String,
    val body: String,
    val tags: List<String>,
    val category: String?,
    val createdAt: Long,
    val updatedAt: Long
)
