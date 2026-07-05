package com.ai.notes.AppFunctions

import android.content.Context
import androidx.appfunctions.AppFunctionContext
import androidx.appfunctions.AppFunctionManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.notes.data.database.NoteDatabase
import com.ai.notes.data.database.repositories.NoteRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests verifying that [NoteFunctions] is correctly registered with, and
 * executable through, the real androidx AppFunctions 1.0.0-alpha09 runtime.
 *
 * Real-API notes (verified via `javap` against the resolved alpha09 AARs, same approach as
 * Tasks 13/14 — see the task-15 report for full decompile output):
 * - Every `@AppFunction`-annotated method on [NoteFunctions] requires an
 *   [AppFunctionContext] as its first parameter (confirmed in Task 13). The brief's original
 *   snippet omitted it; these tests supply a minimal real implementation of the interface
 *   (`getContext(): Context`), since [AppFunctionContext] decompiles to that one-method
 *   interface and no fake/mock library implementation is needed.
 * - The platform-facing invocation entry point is `androidx.appfunctions.AppFunctionManager`
 *   (`getInstance(Context)`, `isAppFunctionEnabled(String, Continuation)`,
 *   `executeAppFunction(ExecuteAppFunctionRequest, Continuation)`), confirmed to exist exactly
 *   as named in the brief's "or whatever the real invocation API is called" caveat. This file
 *   exercises `isAppFunctionEnabled` as a genuine, low-fragility way to confirm the platform
 *   itself recognizes the four registered function ids end-to-end (Task 14's registration +
 *   Task 13's KSP metadata), in addition to the brief's direct in-process execution tests.
 * - Function ids matching the KSP-generated `app_functions_v2.xml` (confirmed present in Task
 *   14) take the form `com.ai.notes.AppFunctions.NoteFunctions#<methodName>`.
 */
@RunWith(AndroidJUnit4::class)
class NoteFunctionsExecutionTest {

    private val functionIds = listOf(
        "com.ai.notes.AppFunctions.NoteFunctions#createNote",
        "com.ai.notes.AppFunctions.NoteFunctions#searchNotes",
        "com.ai.notes.AppFunctions.NoteFunctions#getNote",
        "com.ai.notes.AppFunctions.NoteFunctions#deleteNote",
    )

    private class FakeAppFunctionContext(ctx: Context) : AppFunctionContext {
        override val context: Context = ctx
    }

    private fun newFunctions(): NoteFunctions {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        return NoteFunctions(NoteRepository(db.noteDao()))
    }

    private fun fakeContext(): AppFunctionContext =
        FakeAppFunctionContext(ApplicationProvider.getApplicationContext())

    @Test
    fun createNote_executesAndPersists() = runBlocking {
        val functions = newFunctions()

        val note = functions.createNote(fakeContext(), "Test Title", "Test Body", listOf("t1"), "Cat")

        assertNotNull(note)
        assertEquals("Test Title", note.title)
        assertTrue(note.id > 0)
    }

    @Test
    fun searchNotes_executesAndReturnsMatches() = runBlocking {
        val functions = newFunctions()
        functions.createNote(fakeContext(), "Findable Title", "Body content", emptyList(), null)

        val results = functions.searchNotes(fakeContext(), "Findable")

        assertEquals(1, results.size)
    }

    @Test
    fun getNote_executesAndReturnsNoteOrNull() = runBlocking {
        val functions = newFunctions()
        val created = functions.createNote(fakeContext(), "Title", "Body", emptyList(), null)

        assertEquals(created, functions.getNote(fakeContext(), created.id))
        assertNull(functions.getNote(fakeContext(), -1))
    }

    @Test
    fun deleteNote_executesAndRemovesNote() = runBlocking {
        val functions = newFunctions()
        val created = functions.createNote(fakeContext(), "Title", "Body", emptyList(), null)

        assertTrue(functions.deleteNote(fakeContext(), created.id))
        assertNull(functions.getNote(fakeContext(), created.id))
    }

    @Test
    fun appFunctionsMetadata_assetExistsInApk() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val assetNames = context.assets.list("")
        assertTrue(
            "Expected app_functions_v2.xml in assets, found: ${assetNames?.toList()}",
            assetNames?.contains("app_functions_v2.xml") == true
        )
    }

    /**
     * Verifies the four `NoteFunctions` methods are actually registered with, and enabled by,
     * the platform's real `AppFunctionManager` — not merely present in the KSP-generated XML
     * asset. This is the strongest available end-to-end registration check that does not
     * require hand-building `AppFunctionData` payloads matching the generated schema (the
     * `executeAppFunction` path); it directly asks the platform whether each function id from
     * Task 13/14's registration is recognized and enabled for this package.
     *
     * Requires a device/emulator whose platform (or the AppFunctions extension library) exposes
     * the real AppFunctions service; not executable in this environment (see task-15 report).
     */
    @Test
    fun appFunctions_areRegisteredAndEnabledViaAppFunctionManager() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = AppFunctionManager.getInstance(context)
        assertNotNull("AppFunctionManager.getInstance() returned null; AppFunctions is unsupported on this device", manager)
        requireNotNull(manager)

        for (functionId in functionIds) {
            val enabled = manager.isAppFunctionEnabled(functionId)
            assertTrue("Expected $functionId to be enabled via AppFunctionManager", enabled)
        }
    }
}
