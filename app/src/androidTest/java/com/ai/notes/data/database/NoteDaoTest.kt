package com.ai.notes.data.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.notes.data.database.entities.NoteEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteDaoTest {
    private lateinit var db: NoteDatabase
    private lateinit var dao: NoteDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NoteDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.noteDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun insertAndGetById() = runBlocking {
        val id = dao.insert(
            NoteEntity(title = "Groceries", body = "Milk, eggs", tags = "[]", category = null, createdAt = 1L, updatedAt = 1L)
        )
        val loaded = dao.getById(id.toInt())
        assertEquals("Groceries", loaded?.title)
    }

    @Test
    fun updateModifiesExistingRow() = runBlocking {
        val id = dao.insert(
            NoteEntity(title = "Old", body = "Body", tags = "[]", category = null, createdAt = 1L, updatedAt = 1L)
        )
        val original = dao.getById(id.toInt())!!
        dao.update(original.copy(title = "New"))
        val updated = dao.getById(id.toInt())
        assertEquals("New", updated?.title)
    }

    @Test
    fun deleteByIdRemovesRow() = runBlocking {
        val id = dao.insert(
            NoteEntity(title = "ToDelete", body = "Body", tags = "[]", category = null, createdAt = 1L, updatedAt = 1L)
        )
        dao.deleteById(id.toInt())
        assertNull(dao.getById(id.toInt()))
    }

    @Test
    fun searchMatchesTitleBodyAndTags() = runBlocking {
        dao.insert(NoteEntity(title = "Recipe", body = "Pasta dish", tags = "[\"cooking\"]", category = null, createdAt = 1L, updatedAt = 1L))
        dao.insert(NoteEntity(title = "Meeting", body = "Notes about pasta supplier", tags = "[\"work\"]", category = null, createdAt = 2L, updatedAt = 2L))
        dao.insert(NoteEntity(title = "Unrelated", body = "Nothing here", tags = "[]", category = null, createdAt = 3L, updatedAt = 3L))

        val results = dao.search("pasta").first()
        assertEquals(2, results.size)
    }

    @Test
    fun getAllOrdersByUpdatedAtDescending() = runBlocking {
        dao.insert(NoteEntity(title = "First", body = "B", tags = "[]", category = null, createdAt = 1L, updatedAt = 1L))
        dao.insert(NoteEntity(title = "Second", body = "B", tags = "[]", category = null, createdAt = 2L, updatedAt = 2L))

        val results = dao.getAll().first()
        assertEquals("Second", results.first().title)
    }
}
