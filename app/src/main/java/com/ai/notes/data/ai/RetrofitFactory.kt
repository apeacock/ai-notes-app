package com.ai.notes.data.ai

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

object RetrofitFactory {
    private val json = Json { ignoreUnknownKeys = true }

    fun createClaudeService(): ClaudeService {
        val retrofit = Retrofit.Builder()
            .baseUrl(CLAUDE_BASE_URL)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(ClaudeService::class.java)
    }
}
