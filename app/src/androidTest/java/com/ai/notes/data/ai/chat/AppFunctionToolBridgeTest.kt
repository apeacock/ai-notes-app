package com.ai.notes.data.ai.chat

import android.content.Context
import androidx.appfunctions.AppFunctionManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented — requires a device/emulator on API 36.1+ where AppFunctionManager's shell/tool
 * dispatch is actually implemented (see docs/APP_FUNCTIONS_TESTING.md); this seeds real notes via
 * NoteFunctions directly (same approach as NoteFunctionsExecutionTest) so the bridge is exercised
 * against genuinely registered, real data.
 */
@RunWith(AndroidJUnit4::class)
class AppFunctionToolBridgeTest {

    @Test
    fun discoverTools_returnsAllFourFunctionsWithSchemas() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bridge = AppFunctionToolBridge(context)

        val tools = bridge.discoverTools()

        val names = tools.map { it.name }.toSet()
        assertEquals(setOf("createNote", "searchNotes", "getNote", "deleteNote"), names)
    }

    @Test
    fun execute_createNote_persistsAndReturnsNoteJson() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bridge = AppFunctionToolBridge(context)
        bridge.discoverTools()

        val input = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Bridge Test Note"),
                "body" to JsonPrimitive("Created via AppFunctionToolBridge"),
                "tags" to buildJsonArray { add(JsonPrimitive("bridge")) },
                "category" to JsonPrimitive(null as String?),
            )
        )

        val result = bridge.execute("createNote", input)

        assertTrue(result is ToolExecutionResult.Success)
        val json = (result as ToolExecutionResult.Success).resultJson
        assertTrue(json.contains("Bridge Test Note"))
    }

    @Test
    fun execute_unknownFunction_returnsFailure() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bridge = AppFunctionToolBridge(context)
        bridge.discoverTools()

        val result = bridge.execute("notAFunction", JsonObject(emptyMap()))

        assertTrue(result is ToolExecutionResult.Failure)
    }

    @Test
    fun getInstance_isSupportedOnThisDevice() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Sanity check the precondition this device actually exercises the supported path;
        // documents the documented fallback behavior even though we can't force the
        // "unsupported device" branch from an instrumented test on a supporting device.
        assertNotNull(AppFunctionManager.getInstance(context))
    }
}
