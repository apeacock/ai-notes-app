package com.ai.notes.data.database

import org.junit.Assert.assertEquals
import org.junit.Test

class ConvertersTest {
    private val converters = Converters()

    @Test
    fun `fromTagsList encodes list as JSON array string`() {
        val json = converters.fromTagsList(listOf("work", "urgent"))
        assertEquals("""["work","urgent"]""", json)
    }

    @Test
    fun `toTagsList decodes JSON array string back to list`() {
        val tags = converters.toTagsList("""["work","urgent"]""")
        assertEquals(listOf("work", "urgent"), tags)
    }

    @Test
    fun `toTagsList handles empty array`() {
        val tags = converters.toTagsList("[]")
        assertEquals(emptyList<String>(), tags)
    }

    @Test
    fun `round trip preserves tag order and content`() {
        val original = listOf("a", "b", "c")
        val roundTripped = converters.toTagsList(converters.fromTagsList(original))
        assertEquals(original, roundTripped)
    }
}
