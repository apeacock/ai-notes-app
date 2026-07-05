package com.ai.notes.data.ai.chat

import androidx.appfunctions.metadata.AppFunctionArrayTypeMetadata
import androidx.appfunctions.metadata.AppFunctionBooleanTypeMetadata
import androidx.appfunctions.metadata.AppFunctionDataTypeMetadata
import androidx.appfunctions.metadata.AppFunctionDoubleTypeMetadata
import androidx.appfunctions.metadata.AppFunctionFloatTypeMetadata
import androidx.appfunctions.metadata.AppFunctionIntTypeMetadata
import androidx.appfunctions.metadata.AppFunctionLongTypeMetadata
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.metadata.AppFunctionParameterMetadata
import androidx.appfunctions.metadata.AppFunctionStringTypeMetadata
import com.ai.notes.data.ai.model.ClaudeTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Converts the app's own registered [AppFunctionMetadata] (the same metadata Gemini/Assistant
 * discovers via `adb shell cmd app_function list-app-functions`) into Claude's tool-use JSON
 * schema shape, so the in-app chatbot offers the exact same tools without a second,
 * hand-maintained schema.
 */
object ToolSchemaBuilder {

    fun simpleName(functionId: String): String = functionId.substringAfterLast('#')

    fun buildTool(metadata: AppFunctionMetadata): ClaudeTool = ClaudeTool(
        name = simpleName(metadata.id),
        description = metadata.description,
        inputSchema = buildInputSchema(metadata.parameters),
    )

    private fun buildInputSchema(parameters: List<AppFunctionParameterMetadata>): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                for (parameter in parameters) {
                    putJsonObject(parameter.name) {
                        putTypeFields(parameter.dataType)
                        put("description", JsonPrimitive(parameter.description))
                    }
                }
            }
            putJsonArray("required") {
                for (parameter in parameters) {
                    // isNullable (not isRequired) is the real optionality signal: the platform
                    // marks every parameter isRequired=true, even nullable ones like `category`.
                    if (!parameter.dataType.isNullable) add(JsonPrimitive(parameter.name))
                }
            }
        }

    private fun JsonObjectBuilder.putTypeFields(dataType: AppFunctionDataTypeMetadata) {
        when (dataType) {
            is AppFunctionIntTypeMetadata, is AppFunctionLongTypeMetadata -> put("type", JsonPrimitive("integer"))
            is AppFunctionFloatTypeMetadata, is AppFunctionDoubleTypeMetadata -> put("type", JsonPrimitive("number"))
            is AppFunctionBooleanTypeMetadata -> put("type", JsonPrimitive("boolean"))
            is AppFunctionStringTypeMetadata -> put("type", JsonPrimitive("string"))
            is AppFunctionArrayTypeMetadata -> {
                put("type", JsonPrimitive("array"))
                putJsonObject("items") { putTypeFields(dataType.itemType) }
            }
            else -> put("type", JsonPrimitive("string"))
        }
    }
}
