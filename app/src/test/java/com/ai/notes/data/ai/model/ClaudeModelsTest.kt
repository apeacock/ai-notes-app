package com.ai.notes.data.ai.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ClaudeRequest serializes max_tokens with snake_case key`() {
        val request = ClaudeRequest(
            model = "claude-sonnet-4-20250514",
            maxTokens = 2000,
            messages = listOf(ClaudeMessage(role = "user", content = listOf(ClaudeContentBlock.Text("Summarize this")))),
            thinking = ClaudeThinkingConfig("disabled"),
        )

        val encoded = json.encodeToString(request)

        assertTrue(encoded.contains("\"max_tokens\":2000"))
        assertTrue(encoded.contains("\"model\":\"claude-sonnet-4-20250514\""))
    }

    @Test
    fun `ClaudeRequest omits tools field when null`() {
        val request = ClaudeRequest(
            model = "claude-sonnet-4-20250514",
            maxTokens = 2000,
            messages = listOf(ClaudeMessage(role = "user", content = listOf(ClaudeContentBlock.Text("Hi")))),
            thinking = ClaudeThinkingConfig("disabled"),
        )

        val encoded = json.encodeToString(request)

        assertTrue(!encoded.contains("\"tools\""))
    }

    @Test
    fun `ClaudeRequest serializes thinking as disabled`() {
        val request = ClaudeRequest(
            model = "claude-sonnet-5",
            maxTokens = 2000,
            messages = listOf(ClaudeMessage(role = "user", content = listOf(ClaudeContentBlock.Text("Hi")))),
            thinking = ClaudeThinkingConfig("disabled"),
        )

        val encoded = json.encodeToString(request)

        assertTrue(encoded.contains("\"thinking\":{\"type\":\"disabled\"}"))
    }

    @Test
    fun `ClaudeResponse deserializes a redacted_thinking content block without failing`() {
        val body = """{"content":[{"type":"redacted_thinking","data":"opaque"},{"type":"text","text":"Hello"}]}"""

        val response = json.decodeFromString<ClaudeResponse>(body)

        assertEquals("opaque", (response.content[0] as ClaudeContentBlock.RedactedThinking).data)
        assertEquals("Hello", (response.content[1] as ClaudeContentBlock.Text).text)
    }

    @Test
    fun `ClaudeResponse deserializes a text content block`() {
        val body = """{"content":[{"type":"text","text":"Summary here"}]}"""

        val response = json.decodeFromString<ClaudeResponse>(body)

        assertEquals("Summary here", (response.content[0] as ClaudeContentBlock.Text).text)
    }

    @Test
    fun `ClaudeResponse deserializes a tool_use content block and stop_reason`() {
        val body = """{"content":[{"type":"tool_use","id":"tool_1","name":"searchNotes","input":{"query":"milk"}}],"stop_reason":"tool_use"}"""

        val response = json.decodeFromString<ClaudeResponse>(body)

        val toolUse = response.content[0] as ClaudeContentBlock.ToolUse
        assertEquals("tool_1", toolUse.id)
        assertEquals("searchNotes", toolUse.name)
        assertEquals(JsonPrimitive("milk"), toolUse.input["query"])
        assertEquals("tool_use", response.stopReason)
    }

    @Test
    fun `ClaudeResponse deserializes a thinking block alongside tool_use without failing`() {
        val body = """{"content":[{"type":"thinking","thinking":"reasoning...","signature":"abc123"},{"type":"tool_use","id":"tool_1","name":"searchNotes","input":{"query":"milk"}}],"stop_reason":"tool_use"}"""

        val response = json.decodeFromString<ClaudeResponse>(body)

        assertEquals("reasoning...", (response.content[0] as ClaudeContentBlock.Thinking).thinking)
        assertEquals("searchNotes", (response.content[1] as ClaudeContentBlock.ToolUse).name)
    }

    @Test
    fun `ClaudeMessage round-trips a thinking block's signature when echoed back as history`() {
        val body = """{"content":[{"type":"thinking","thinking":"reasoning...","signature":"abc123"}]}"""
        val response = json.decodeFromString<ClaudeResponse>(body)
        val message = ClaudeMessage(role = "assistant", content = response.content)

        val encoded = json.encodeToString(message)

        assertTrue(
            "Expected signature to survive re-serialization, got: $encoded",
            encoded.contains("\"signature\":\"abc123\""),
        )
    }

    @Test
    fun `ClaudeMessage serializes a tool_result content block`() {
        val message = ClaudeMessage(
            role = "user",
            content = listOf(ClaudeContentBlock.ToolResult(toolUseId = "tool_1", content = "[]", isError = false))
        )

        val encoded = json.encodeToString(message)

        assertTrue(encoded.contains("\"type\":\"tool_result\""))
        assertTrue(encoded.contains("\"tool_use_id\":\"tool_1\""))
    }
}
