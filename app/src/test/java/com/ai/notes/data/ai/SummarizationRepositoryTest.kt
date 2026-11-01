package com.ai.notes.data.ai

import android.content.Context
import com.ai.notes.data.ai.model.ClaudeContentBlock
import com.ai.notes.data.ai.model.ClaudeMessage
import com.ai.notes.data.ai.model.ClaudeRequest
import com.ai.notes.data.ai.model.ClaudeThinkingConfig
import com.ai.notes.data.ai.model.ClaudeResponse
import com.ai.notes.data.model.Note
import com.ai.notes.data.preferences.ApiKeyManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

private fun note(n: Int) = Note(
    id = n,
    title = "Title $n",
    body = "Body $n",
    tags = emptyList(),
    category = null,
    createdAt = 0L,
    updatedAt = 0L
)

class SummarizationRepositoryTest {
    private fun fakeApiKeyManager(key: String?): ApiKeyManager {
        val context = mockk<Context>(relaxed = true)
        val manager = mockk<ApiKeyManager>()
        every { manager.getApiKey() } returns key
        return manager
    }

    @Test
    fun `summarize returns Failure InvalidApiKey when no key stored`() = runTest {
        val service = mockk<ClaudeService>()
        val repository = SummarizationRepository(service, fakeApiKeyManager(null))

        val result = repository.summarize(listOf(note(1), note(2)))

        assertTrue(result is SummarizeResult.Failure)
        assertEquals(AppError.InvalidApiKey, (result as SummarizeResult.Failure).error)
    }

    @Test
    fun `summarize returns Success with extracted text on 200 response`() = runTest {
        val service = mockk<ClaudeService>()
        val response = Response.success(ClaudeResponse(content = listOf(ClaudeContentBlock.Text("Summary text"))))
        coEvery { service.sendMessage(any(), any(), any()) } returns response
        val repository = SummarizationRepository(service, fakeApiKeyManager("sk-ant-key"))

        val result = repository.summarize(listOf(note(1), note(2)))

        assertTrue(result is SummarizeResult.Success)
        assertEquals("Summary text", (result as SummarizeResult.Success).summary)
    }

    @Test
    fun `summarize returns Failure InvalidApiKey on HTTP 401`() = runTest {
        val service = mockk<ClaudeService>()
        val errorResponse = Response.error<ClaudeResponse>(
            401,
            "{}".toResponseBody("application/json".toMediaType())
        )
        coEvery { service.sendMessage(any(), any(), any()) } returns errorResponse
        val repository = SummarizationRepository(service, fakeApiKeyManager("sk-ant-key"))

        val result = repository.summarize(listOf(note(1), note(2)))

        assertTrue(result is SummarizeResult.Failure)
        assertEquals(AppError.InvalidApiKey, (result as SummarizeResult.Failure).error)
    }

    @Test
    fun `summarize returns Failure ServerError on HTTP 500`() = runTest {
        val service = mockk<ClaudeService>()
        val errorResponse = Response.error<ClaudeResponse>(
            500,
            "{}".toResponseBody("application/json".toMediaType())
        )
        coEvery { service.sendMessage(any(), any(), any()) } returns errorResponse
        val repository = SummarizationRepository(service, fakeApiKeyManager("sk-ant-key"))

        val result = repository.summarize(listOf(note(1), note(2)))

        assertTrue(result is SummarizeResult.Failure)
        assertEquals(AppError.ServerError, (result as SummarizeResult.Failure).error)
    }

    @Test
    fun `summarize maps thrown IOException via ErrorMapper`() = runTest {
        val service = mockk<ClaudeService>()
        coEvery { service.sendMessage(any(), any(), any()) } throws java.net.SocketTimeoutException()
        val repository = SummarizationRepository(service, fakeApiKeyManager("sk-ant-key"))

        val result = repository.summarize(listOf(note(1), note(2)))

        assertTrue(result is SummarizeResult.Failure)
        assertEquals(AppError.Timeout, (result as SummarizeResult.Failure).error)
    }

    @Test
    fun `summarize treats an empty stored key as missing and never calls the API`() = runTest {
        val service = mockk<ClaudeService>()
        val repository = SummarizationRepository(service, fakeApiKeyManager(""))

        val result = repository.summarize(listOf(note(1), note(2)))

        assertEquals(SummarizeResult.Failure(AppError.InvalidApiKey), result)
        coVerify(exactly = 0) { service.sendMessage(any(), any(), any()) }
    }

    @Test
    fun `summarize sends the prompt with the stored key, model, token cap and thinking disabled`() = runTest {
        val service = mockk<ClaudeService>()
        val request = slot<ClaudeRequest>()
        coEvery { service.sendMessage("sk-ant-key", CLAUDE_API_VERSION, capture(request)) } returns
            Response.success(ClaudeResponse(content = listOf(ClaudeContentBlock.Text("ok"))))
        val notes = listOf(note(1), note(2))

        SummarizationRepository(service, fakeApiKeyManager("sk-ant-key")).summarize(notes)

        assertEquals(
            ClaudeRequest(
                model = CLAUDE_MODEL,
                maxTokens = 2000,
                messages = listOf(
                    ClaudeMessage("user", listOf(ClaudeContentBlock.Text(BatchSummarizer.buildPrompt(notes))))
                ),
                thinking = ClaudeThinkingConfig("disabled"),
            ),
            request.captured,
        )
    }

    @Test
    fun `summarize returns the first text block, skipping non-text blocks`() = runTest {
        val service = mockk<ClaudeService>()
        coEvery { service.sendMessage(any(), any(), any()) } returns Response.success(
            ClaudeResponse(
                content = listOf(
                    ClaudeContentBlock.Thinking("hmm"),
                    ClaudeContentBlock.Text("First"),
                    ClaudeContentBlock.Text("Second"),
                )
            )
        )

        val result = SummarizationRepository(service, fakeApiKeyManager("k")).summarize(listOf(note(1), note(2)))

        assertEquals(SummarizeResult.Success("First"), result)
    }

    @Test
    fun `summarize returns UnknownNetwork when a successful response has no text`() = runTest {
        val service = mockk<ClaudeService>()
        coEvery { service.sendMessage(any(), any(), any()) } returns
            Response.success(ClaudeResponse(content = listOf(ClaudeContentBlock.Thinking("hmm"))))

        val result = SummarizationRepository(service, fakeApiKeyManager("k")).summarize(listOf(note(1), note(2)))

        assertEquals(SummarizeResult.Failure(AppError.UnknownNetwork), result)
    }

    @Test
    fun `summarize returns UnknownNetwork when a successful response has no body`() = runTest {
        val service = mockk<ClaudeService>()
        coEvery { service.sendMessage(any(), any(), any()) } returns Response.success<ClaudeResponse>(null)

        val result = SummarizationRepository(service, fakeApiKeyManager("k")).summarize(listOf(note(1), note(2)))

        assertEquals(SummarizeResult.Failure(AppError.UnknownNetwork), result)
    }
}
