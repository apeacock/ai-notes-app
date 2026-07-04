package com.ai.notes.data.database.repositories

import com.ai.notes.data.database.NoteDao
import com.ai.notes.data.database.entities.toEntity
import com.ai.notes.data.database.entities.toNote
import com.ai.notes.data.model.Note
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class NoteRepository(private val noteDao: NoteDao) {

    fun getAllNotes(): Flow<List<Note>> =
        noteDao.getAll().map { entities -> entities.map { it.toNote() } }

    fun searchNotes(query: String): Flow<List<Note>> =
        noteDao.search(query).map { entities -> entities.map { it.toNote() } }

    suspend fun getNoteById(id: Int): Note? = noteDao.getById(id)?.toNote()

    suspend fun createNote(title: String, body: String, tags: List<String>, category: String?): Note {
        val now = System.currentTimeMillis()
        val note = Note(
            title = title,
            body = body,
            tags = tags,
            category = category,
            createdAt = now,
            updatedAt = now
        )
        val id = noteDao.insert(note.toEntity())
        return note.copy(id = id.toInt())
    }

    suspend fun updateNote(note: Note) {
        val updated = note.copy(updatedAt = System.currentTimeMillis())
        noteDao.update(updated.toEntity())
    }

    suspend fun deleteNote(id: Int) {
        noteDao.deleteById(id)
    }
}
