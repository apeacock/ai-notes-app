package com.ai.notes.data.ai

import android.content.Context
import com.ai.notes.data.ai.model.ClaudeContentBlock
import com.ai.notes.data.ai.model.ClaudeRequest
import com.ai.notes.data.ai.model.ClaudeResponse
import com.ai.notes.data.model.Note
import com.ai.notes.data.preferences.ApiKeyManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
        val response = Response.success(ClaudeResponse(content = listOf(ClaudeContentBlock("text", "Summary text"))))
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
}
