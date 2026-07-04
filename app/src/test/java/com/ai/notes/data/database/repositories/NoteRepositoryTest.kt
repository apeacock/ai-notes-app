package com.ai.notes.data.database.repositories

import com.ai.notes.data.database.NoteDao
import com.ai.notes.data.database.entities.NoteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeNoteDao : NoteDao {
    val stored = MutableStateFlow<List<NoteEntity>>(emptyList())
    private var nextId = 1

    override suspend fun insert(note: NoteEntity): Long {
        val withId = note.copy(id = nextId++)
        stored.value = stored.value + withId
        return withId.id.toLong()
    }

    override suspend fun update(note: NoteEntity) {
        stored.value = stored.value.map { if (it.id == note.id) note else it }
    }

    override suspend fun delete(note: NoteEntity) {
        stored.value = stored.value.filterNot { it.id == note.id }
    }

    override suspend fun deleteById(id: Int) {
        stored.value = stored.value.filterNot { it.id == id }
    }

    override suspend fun getById(id: Int): NoteEntity? = stored.value.find { it.id == id }

    override fun getAll(): Flow<List<NoteEntity>> = stored

    override fun search(query: String): Flow<List<NoteEntity>> = MutableStateFlow(
        stored.value.filter {
            it.title.contains(query, ignoreCase = true) || it.body.contains(query, ignoreCase = true)
        }
    )
}

class NoteRepositoryTest {
    @Test
    fun `createNote inserts and returns Note with generated id`() = runTest {
        val repository = NoteRepository(FakeNoteDao())

        val note = repository.createNote("Title", "Body", listOf("tag"), "Cat")

        assertEquals("Title", note.title)
        assertEquals(1, note.id)
    }

    @Test
    fun `getNoteById returns null for missing id`() = runTest {
        val repository = NoteRepository(FakeNoteDao())
        assertNull(repository.getNoteById(999))
    }

    @Test
    fun `updateNote persists changes`() = runTest {
        val repository = NoteRepository(FakeNoteDao())
        val note = repository.createNote("Title", "Body", emptyList(), null)

        repository.updateNote(note.copy(title = "Updated"))

        val reloaded = repository.getNoteById(note.id)
        assertEquals("Updated", reloaded?.title)
    }

    @Test
    fun `deleteNote removes the note`() = runTest {
        val repository = NoteRepository(FakeNoteDao())
        val note = repository.createNote("Title", "Body", emptyList(), null)

        repository.deleteNote(note.id)

        assertNull(repository.getNoteById(note.id))
    }

    @Test
    fun `getAllNotes emits mapped domain notes`() = runTest {
        val repository = NoteRepository(FakeNoteDao())
        repository.createNote("Title", "Body", listOf("x"), null)

        val notes = repository.getAllNotes().first()

        assertEquals(1, notes.size)
        assertEquals(listOf("x"), notes.first().tags)
    }

    @Test
    fun `searchNotes filters by title and body`() = runTest {
        val repository = NoteRepository(FakeNoteDao())
        repository.createNote("Recipe", "Pasta", emptyList(), null)
        repository.createNote("Unrelated", "Nothing", emptyList(), null)

        val results = repository.searchNotes("pasta").first()

        assertEquals(1, results.size)
    }
}
