package com.ai.notes.data.ai

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

object RetrofitFactory {
    private val json = Json { ignoreUnknownKeys = true }

    // OkHttp's default 10s read timeout is far too short for non-streaming Claude
    // responses — generating a 2000-token reply routinely takes tens of seconds.
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun createClaudeService(): ClaudeService {
        val retrofit = Retrofit.Builder()
            .baseUrl(CLAUDE_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(ClaudeService::class.java)
    }
}
