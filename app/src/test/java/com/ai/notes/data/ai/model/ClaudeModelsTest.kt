package com.ai.notes.data.ai.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ClaudeModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ClaudeRequest serializes max_tokens with snake_case key`() {
        val request = ClaudeRequest(
            model = "claude-sonnet-4-20250514",
            maxTokens = 2000,
            messages = listOf(ClaudeMessage(role = "user", content = "Summarize this"))
        )

        val encoded = json.encodeToString(request)

        assertEquals(true, encoded.contains("\"max_tokens\":2000"))
        assertEquals(true, encoded.contains("\"model\":\"claude-sonnet-4-20250514\""))
    }

    @Test
    fun `ClaudeResponse deserializes content text`() {
        val body = """{"content":[{"type":"text","text":"Summary here"}]}"""

        val response = json.decodeFromString<ClaudeResponse>(body)

        assertEquals("Summary here", response.content[0].text)
    }
}
