package com.ai.notes.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.ai.notes.data.database.Converters
import com.ai.notes.data.model.Note

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val body: String,
    val tags: String,
    val category: String?,
    val createdAt: Long,
    val updatedAt: Long
)

private val mappingConverters = Converters()

fun NoteEntity.toNote(): Note = Note(
    id = id,
    title = title,
    body = body,
    tags = mappingConverters.toTagsList(tags),
    category = category,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Note.toEntity(): NoteEntity = NoteEntity(
    id = id,
    title = title,
    body = body,
    tags = mappingConverters.fromTagsList(tags),
    category = category,
    createdAt = createdAt,
    updatedAt = updatedAt
)
