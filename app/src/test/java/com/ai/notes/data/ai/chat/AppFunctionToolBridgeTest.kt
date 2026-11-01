package com.ai.notes.data.ai.chat

import android.content.Context
import androidx.appfunctions.AppFunctionManager
import androidx.appfunctions.AppFunctionSearchSpec
import androidx.appfunctions.metadata.AppFunctionDataTypeMetadata
import androidx.appfunctions.metadata.AppFunctionIntTypeMetadata
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.metadata.AppFunctionPackageMetadata
import androidx.appfunctions.metadata.AppFunctionParameterMetadata
import androidx.appfunctions.metadata.AppFunctionResponseMetadata
import androidx.appfunctions.metadata.AppFunctionStringTypeMetadata
import androidx.appfunctions.metadata.AppFunctionUnitTypeMetadata
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

private const val PACKAGE = "com.ai.notes"

private fun metadata(
    name: String,
    parameters: List<AppFunctionParameterMetadata> = emptyList(),
    returns: AppFunctionDataTypeMetadata = AppFunctionUnitTypeMetadata(isNullable = false),
) = AppFunctionMetadata(
    id = "com.ai.notes.AppFunctions.NoteFunctions#$name",
    packageName = PACKAGE,
    isEnabled = true,
    schema = null,
    parameters = parameters,
    response = AppFunctionResponseMetadata(valueType = returns),
    description = "$name description",
)

private fun param(name: String, type: AppFunctionDataTypeMetadata) =
    AppFunctionParameterMetadata(name = name, isRequired = true, dataType = type, description = name)

/**
 * Covers discovery and the guard clauses only. Anything that reaches buildParameters /
 * readReturnValue touches AppFunctionData, whose static initializer calls the framework's
 * GenericDocument.Builder.build() and cannot load on the plain JVM (even to be mocked); those
 * paths need Robolectric or an instrumented test.
 */
class AppFunctionToolBridgeTest {
    private val context = mockk<Context>(relaxed = true)
    private val manager = mockk<AppFunctionManager>()

    @Before
    fun setup() {
        every { context.packageName } returns PACKAGE
        mockkObject(AppFunctionManager.Companion)
        every { AppFunctionManager.getInstance(context) } returns manager
    }

    @After
    fun teardown() = unmockkAll()

    /** Registers [functions] with the fake manager and runs discovery so execute() can find them. */
    private suspend fun bridgeWith(vararg functions: AppFunctionMetadata): AppFunctionToolBridge {
        every { manager.observeAppFunctions(any()) } returns
            flowOf(listOf(AppFunctionPackageMetadata(PACKAGE, functions.toList())))
        return AppFunctionToolBridge(context).also { it.discoverTools() }
    }

    @Test
    fun `discoverTools returns no tools when AppFunctions is unavailable`() = runTest {
        every { AppFunctionManager.getInstance(context) } returns null

        assertEquals(emptyList<Any>(), AppFunctionToolBridge(context).discoverTools())
    }

    @Test
    fun `discoverTools searches this package and converts every function to a tool`() = runTest {
        val create = metadata("createNote", listOf(param("title", AppFunctionStringTypeMetadata(false))))
        val delete = metadata("deleteNote", listOf(param("noteId", AppFunctionIntTypeMetadata(false))))
        val spec = slot<AppFunctionSearchSpec>()
        every { manager.observeAppFunctions(capture(spec)) } returns
            flowOf(listOf(AppFunctionPackageMetadata(PACKAGE, listOf(create, delete))))

        val tools = AppFunctionToolBridge(context).discoverTools()

        assertEquals(listOf(ToolSchemaBuilder.buildTool(create), ToolSchemaBuilder.buildTool(delete)), tools)
        assertEquals(setOf(PACKAGE), spec.captured.packageNames)
    }

    @Test
    fun `execute fails when AppFunctions is unavailable`() = runTest {
        every { AppFunctionManager.getInstance(context) } returns null

        val result = AppFunctionToolBridge(context).execute("createNote", JsonObject(emptyMap()))

        assertEquals(ToolExecutionResult.Failure("AppFunctions is not available on this device."), result)
    }

    @Test
    fun `execute fails for a function that was not discovered`() = runTest {
        val bridge = bridgeWith(metadata("createNote"))

        val result = bridge.execute("dropTable", JsonObject(emptyMap()))

        assertEquals(ToolExecutionResult.Failure("Function 'dropTable' was not found."), result)
    }
}
