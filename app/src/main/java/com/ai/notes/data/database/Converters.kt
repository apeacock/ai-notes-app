package com.ai.notes.data.database

import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromTagsList(tags: List<String>): String {
        return json.encodeToString(tags)
    }

    @TypeConverter
    fun toTagsList(value: String): List<String> {
        return json.decodeFromString(value)
    }
}
