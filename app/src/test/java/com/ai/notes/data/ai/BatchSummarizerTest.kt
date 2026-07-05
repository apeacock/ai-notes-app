package com.ai.notes.data.ai

import com.ai.notes.data.model.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchSummarizerTest {
    private fun note(n: Int) = Note(
        id = n,
        title = "Title $n",
        body = "Body $n",
        tags = emptyList(),
        category = null,
        createdAt = 0L,
        updatedAt = 0L
    )

    @Test
    fun `validateSelection returns EmptyBatch for fewer than 2 notes`() {
        assertEquals(AppError.EmptyBatch, BatchSummarizer.validateSelection(1))
        assertEquals(AppError.EmptyBatch, BatchSummarizer.validateSelection(0))
    }

    @Test
    fun `validateSelection returns BatchTooLarge for more than 10 notes`() {
        assertEquals(AppError.BatchTooLarge, BatchSummarizer.validateSelection(11))
    }

    @Test
    fun `validateSelection returns null for 2 to 10 notes`() {
        assertNull(BatchSummarizer.validateSelection(2))
        assertNull(BatchSummarizer.validateSelection(10))
        assertNull(BatchSummarizer.validateSelection(5))
    }

    @Test
    fun `buildPrompt includes all note titles and bodies in order`() {
        val notes = listOf(note(1), note(2), note(3))

        val prompt = BatchSummarizer.buildPrompt(notes)

        assertTrue(prompt.startsWith("Summarize the following notes:"))
        assertTrue(prompt.contains("Title 1"))
        assertTrue(prompt.contains("Body 1"))
        assertTrue(prompt.contains("Title 3"))
        assertTrue(prompt.indexOf("Title 1") < prompt.indexOf("Title 2"))
        assertTrue(prompt.indexOf("Title 2") < prompt.indexOf("Title 3"))
    }

    @Test
    fun `MIN_NOTES and MAX_NOTES constants match spec`() {
        assertEquals(2, BatchSummarizer.MIN_NOTES)
        assertEquals(10, BatchSummarizer.MAX_NOTES)
    }
}
