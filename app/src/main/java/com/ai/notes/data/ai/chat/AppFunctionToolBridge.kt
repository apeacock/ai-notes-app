package com.ai.notes.data.ai.chat

import android.content.Context
import androidx.appfunctions.AppFunctionData
import androidx.appfunctions.AppFunctionManager
import androidx.appfunctions.AppFunctionSearchSpec
import androidx.appfunctions.ExecuteAppFunctionRequest
import androidx.appfunctions.ExecuteAppFunctionResponse
import androidx.appfunctions.metadata.AppFunctionArrayTypeMetadata
import androidx.appfunctions.metadata.AppFunctionBooleanTypeMetadata
import androidx.appfunctions.metadata.AppFunctionDoubleTypeMetadata
import androidx.appfunctions.metadata.AppFunctionFloatTypeMetadata
import androidx.appfunctions.metadata.AppFunctionIntTypeMetadata
import androidx.appfunctions.metadata.AppFunctionLongTypeMetadata
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.metadata.AppFunctionReferenceTypeMetadata
import com.ai.notes.data.ai.model.ClaudeTool
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * The in-process bridge between Claude's tool-use loop and the app's own registered AppFunctions,
 * modeled on https://github.com/philipplackner/AppFunctionsDemo's AppFunctionRunner: it discovers
 * and executes the exact same functions Gemini/Assistant would via AppFunctionManager, so there is
 * one source of truth for what "createNote" etc. mean.
 */
class AppFunctionToolBridge(private val context: Context) : ToolBridge {
    private val packageName: String = context.packageName
    private var cachedMetadata: Map<String, AppFunctionMetadata> = emptyMap()

    override suspend fun discoverTools(): List<ClaudeTool> {
        val manager = AppFunctionManager.getInstance(context) ?: return emptyList()
        val spec = AppFunctionSearchSpec(packageNames = setOf(packageName))
        val metadataList = manager.observeAppFunctions(spec).first().flatMap { it.appFunctions }
        cachedMetadata = metadataList.associateBy { ToolSchemaBuilder.simpleName(it.id) }
        return metadataList.map { ToolSchemaBuilder.buildTool(it) }
    }

    override suspend fun execute(functionName: String, input: JsonObject): ToolExecutionResult {
        val manager = AppFunctionManager.getInstance(context)
            ?: return ToolExecutionResult.Failure("AppFunctions is not available on this device.")
        val metadata = cachedMetadata[functionName]
            ?: return ToolExecutionResult.Failure("Function '$functionName' was not found.")

        val parameters = buildParameters(metadata, input)
        val request = ExecuteAppFunctionRequest(packageName, metadata.id, parameters)

        return when (val response = manager.executeAppFunction(request)) {
            is ExecuteAppFunctionResponse.Success ->
                ToolExecutionResult.Success(readReturnValue(metadata, response.returnValue))
            is ExecuteAppFunctionResponse.Error ->
                ToolExecutionResult.Failure(response.error.errorMessage ?: "Function execution failed.")
        }
    }

    private fun buildParameters(metadata: AppFunctionMetadata, input: JsonObject): AppFunctionData {
        val builder = AppFunctionData.Builder(metadata.parameters, metadata.components)
        for (parameter in metadata.parameters) {
            val value = input[parameter.name] ?: continue
            if (value is kotlinx.serialization.json.JsonNull) continue
            when (parameter.dataType) {
                is AppFunctionIntTypeMetadata -> builder.setInt(parameter.name, value.jsonPrimitive.int)
                is AppFunctionLongTypeMetadata -> builder.setLong(parameter.name, value.jsonPrimitive.long)
                is AppFunctionFloatTypeMetadata -> builder.setFloat(parameter.name, value.jsonPrimitive.float)
                is AppFunctionDoubleTypeMetadata -> builder.setDouble(parameter.name, value.jsonPrimitive.double)
                is AppFunctionBooleanTypeMetadata -> builder.setBoolean(parameter.name, value.jsonPrimitive.boolean)
                is AppFunctionArrayTypeMetadata ->
                    builder.setStringList(parameter.name, value.jsonArray.map { it.jsonPrimitive.content })
                else -> value.jsonPrimitive.contentOrNull?.let { builder.setString(parameter.name, it) }
            }
        }
        return builder.build()
    }

    private fun readReturnValue(metadata: AppFunctionMetadata, data: AppFunctionData): String {
        val key = ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE
        return when (metadata.response.valueType) {
            is AppFunctionBooleanTypeMetadata -> data.getBoolean(key).toString()
            is AppFunctionIntTypeMetadata -> data.getInt(key).toString()
            is AppFunctionLongTypeMetadata -> data.getLong(key).toString()
            is AppFunctionReferenceTypeMetadata ->
                data.getAppFunctionData(key)?.let { noteJson(it).toString() } ?: "null"
            is AppFunctionArrayTypeMetadata ->
                "[" + (data.getAppFunctionDataList(key) ?: emptyList()).joinToString(",") { noteJson(it).toString() } + "]"
            else -> data.getString(key) ?: "null"
        }
    }

    /**
     * `Note` is the only custom type in this app's AppFunctions schema, so a small dedicated
     * extractor covers it — a generic recursive AppFunctionData-to-JSON converter would be
     * speculative generality with nothing else to justify it.
     */
    private fun noteJson(note: AppFunctionData): JsonObject = buildJsonObject {
        put("id", note.getInt("id"))
        put("title", note.getString("title") ?: "")
        put("body", note.getString("body") ?: "")
        put("tags", kotlinx.serialization.json.JsonArray((note.getStringList("tags") ?: emptyList()).map { JsonPrimitive(it) }))
        note.getString("category")?.let { put("category", it) }
        put("createdAt", note.getLong("createdAt"))
        put("updatedAt", note.getLong("updatedAt"))
    }
}
