package com.ai.notes.AppFunctions

import androidx.appfunctions.AppFunctionContext
import com.ai.notes.data.database.NoteDao
import com.ai.notes.data.database.entities.NoteEntity
import com.ai.notes.data.database.repositories.NoteRepository
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        stored.value.filter { it.title.contains(query, ignoreCase = true) || it.body.contains(query, ignoreCase = true) }
    )
}

class NoteFunctionsTest {

    // AppFunctionContext is a plain interface (just wraps an Android Context) so it can be
    // relaxed-mocked for JVM unit tests; NoteFunctions' implementations don't touch it directly.
    private val fakeContext: AppFunctionContext = mockk(relaxed = true)

    @Test
    fun `createNote returns Note with generated id`() = runTest {
        val functions = NoteFunctions(NoteRepository(FakeNoteDao()))

        val note = functions.createNote(fakeContext, "Title", "Body", listOf("tag"), "Category")

        assertEquals("Title", note.title)
        assertTrue(note.id > 0)
    }

    @Test
    fun `searchNotes returns matching notes`() = runTest {
        val functions = NoteFunctions(NoteRepository(FakeNoteDao()))
        functions.createNote(fakeContext, "Recipe", "Pasta dish", emptyList(), null)
        functions.createNote(fakeContext, "Unrelated", "Nothing", emptyList(), null)

        val results = functions.searchNotes(fakeContext, "pasta")

        assertEquals(1, results.size)
    }

    @Test
    fun `getNote returns note by id or null`() = runTest {
        val functions = NoteFunctions(NoteRepository(FakeNoteDao()))
        val created = functions.createNote(fakeContext, "Title", "Body", emptyList(), null)

        assertEquals(created, functions.getNote(fakeContext, created.id))
        assertNull(functions.getNote(fakeContext, 9999))
    }

    @Test
    fun `deleteNote returns true after deleting existing note`() = runTest {
        val functions = NoteFunctions(NoteRepository(FakeNoteDao()))
        val created = functions.createNote(fakeContext, "Title", "Body", emptyList(), null)

        val result = functions.deleteNote(fakeContext, created.id)

        assertTrue(result)
        assertNull(functions.getNote(fakeContext, created.id))
    }

    @Test
    fun `deleteNote returns false when note does not exist`() = runTest {
        val functions = NoteFunctions(NoteRepository(FakeNoteDao()))

        assertFalse(functions.deleteNote(fakeContext, 9999))
    }
}
