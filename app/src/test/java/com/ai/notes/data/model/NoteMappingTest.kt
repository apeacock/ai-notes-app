package com.ai.notes.data.model

import com.ai.notes.data.database.entities.toEntity
import com.ai.notes.data.database.entities.toNote
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteMappingTest {
    @Test
    fun `Note maps to NoteEntity and back without data loss`() {
        val note = Note(
            id = 1,
            title = "Title",
            body = "Body text",
            tags = listOf("tag1", "tag2"),
            category = "Personal",
            createdAt = 1000L,
            updatedAt = 2000L
        )

        val roundTripped = note.toEntity().toNote()

        assertEquals(note, roundTripped)
    }

    @Test
    fun `null category is preserved through mapping`() {
        val note = Note(
            title = "T",
            body = "B",
            tags = emptyList(),
            category = null,
            createdAt = 0L,
            updatedAt = 0L
        )

        assertEquals(null, note.toEntity().toNote().category)
    }
}
