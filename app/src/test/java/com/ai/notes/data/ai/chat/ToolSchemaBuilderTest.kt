package com.ai.notes.data.ai.chat

import androidx.appfunctions.metadata.AppFunctionArrayTypeMetadata
import androidx.appfunctions.metadata.AppFunctionIntTypeMetadata
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.metadata.AppFunctionParameterMetadata
import androidx.appfunctions.metadata.AppFunctionResponseMetadata
import androidx.appfunctions.metadata.AppFunctionStringTypeMetadata
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSchemaBuilderTest {

    private fun createNoteMetadata(): AppFunctionMetadata = AppFunctionMetadata(
        id = "com.ai.notes.AppFunctions.NoteFunctions#createNote",
        packageName = "com.ai.notes",
        isEnabled = true,
        schema = null,
        parameters = listOf(
            AppFunctionParameterMetadata(
                name = "title",
                isRequired = true,
                dataType = AppFunctionStringTypeMetadata(isNullable = false),
                description = "The note's title.",
            ),
            AppFunctionParameterMetadata(
                name = "tags",
                isRequired = true,
                dataType = AppFunctionArrayTypeMetadata(
                    itemType = AppFunctionStringTypeMetadata(isNullable = false),
                    isNullable = false,
                ),
                description = "Tags for the note.",
            ),
            AppFunctionParameterMetadata(
                name = "category",
                isRequired = true,
                dataType = AppFunctionStringTypeMetadata(isNullable = true),
                description = "Optional category.",
            ),
        ),
        response = AppFunctionResponseMetadata(valueType = AppFunctionStringTypeMetadata(isNullable = false)),
        description = "Creates a new note with a title, body, optional tags, and an optional category.",
    )

    @Test
    fun `simpleName extracts the method name from a qualified function id`() {
        assertEquals(
            "createNote",
            ToolSchemaBuilder.simpleName("com.ai.notes.AppFunctions.NoteFunctions#createNote"),
        )
    }

    @Test
    fun `buildTool uses the simple name and copies the description`() {
        val tool = ToolSchemaBuilder.buildTool(createNoteMetadata())

        assertEquals("createNote", tool.name)
        assertEquals(
            "Creates a new note with a title, body, optional tags, and an optional category.",
            tool.description,
        )
    }

    @Test
    fun `buildTool marks non-nullable string parameter as required string type`() {
        val tool = ToolSchemaBuilder.buildTool(createNoteMetadata())

        val titleSchema = tool.inputSchema["properties"]!!.jsonObject["title"]!!.jsonObject
        assertEquals("string", titleSchema["type"]!!.jsonPrimitive.content)
        val required = tool.inputSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("title" in required)
    }

    @Test
    fun `buildTool excludes a nullable parameter from required`() {
        val tool = ToolSchemaBuilder.buildTool(createNoteMetadata())

        val required = tool.inputSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertFalse("category" in required)
    }

    @Test
    fun `buildTool encodes a List of String parameter as an array of string`() {
        val tool = ToolSchemaBuilder.buildTool(createNoteMetadata())

        val tagsSchema = tool.inputSchema["properties"]!!.jsonObject["tags"]!!.jsonObject
        assertEquals("array", tagsSchema["type"]!!.jsonPrimitive.content)
        assertEquals("string", tagsSchema["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildTool encodes an int parameter as integer type`() {
        val metadata = AppFunctionMetadata(
            id = "com.ai.notes.AppFunctions.NoteFunctions#getNote",
            packageName = "com.ai.notes",
            isEnabled = true,
            schema = null,
            parameters = listOf(
                AppFunctionParameterMetadata(
                    name = "noteId",
                    isRequired = true,
                    dataType = AppFunctionIntTypeMetadata(isNullable = false),
                    description = "The id of the note to retrieve.",
                ),
            ),
            response = AppFunctionResponseMetadata(valueType = AppFunctionStringTypeMetadata(isNullable = true)),
            description = "Retrieves a single note by its unique id.",
        )

        val tool = ToolSchemaBuilder.buildTool(metadata)

        val noteIdSchema = tool.inputSchema["properties"]!!.jsonObject["noteId"]!!.jsonObject
        assertEquals("integer", noteIdSchema["type"]!!.jsonPrimitive.content)
    }
}
