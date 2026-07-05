package com.ai.notes.AppFunctions

import androidx.appfunctions.AppFunctionContext
import androidx.appfunctions.service.AppFunction
import com.ai.notes.data.database.repositories.NoteRepository
import com.ai.notes.data.model.Note
import kotlinx.coroutines.flow.first

/**
 * Exposes the app's note CRUD operations to system AI assistants via
 * androidx AppFunctions.
 *
 * Each method annotated with [AppFunction] is discovered and invoked by the
 * platform's assistant infrastructure (e.g. Gemini/Assistant integrations),
 * not by in-app UI code. The KDoc on each function becomes the function's
 * user-facing metadata because every annotation below sets
 * `isDescribedByKDoc = true`, so the wording must clearly describe intent and
 * usage for an AI system deciding when to call it.
 */
class NoteFunctions(private val repository: NoteRepository) {

    /**
     * Creates a new note with a title, body, optional tags, and an optional category.
     *
     * Use this when the user wants to jot down, save, write, or record a new note,
     * memo, reminder, or piece of information.
     *
     * @param appFunctionContext Execution context supplied by the AppFunctions platform.
     * @param title The note's title.
     * @param body The note's main text content.
     * @param tags Labels to attach to the note for later filtering or search. Pass an empty list when the user gives none.
     * @param category An optional user-defined category grouping this note with similar ones. Pass null when the user gives none.
     * @return The created note, including its generated id and creation/update timestamps.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun createNote(
        appFunctionContext: AppFunctionContext,
        title: String,
        body: String,
        tags: List<String>,
        category: String?,
    ): Note {
        return repository.createNote(title, body, tags, category)
    }

    /**
     * Searches existing notes by a keyword, matching against title, body, and tags.
     *
     * Use this when the user wants to find, look up, search for, or recall notes
     * matching a topic or word.
     *
     * @param appFunctionContext Execution context supplied by the AppFunctions platform.
     * @param query The keyword or phrase to search for.
     * @return The notes whose title, body, or tags match the query, most recently updated first.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun searchNotes(appFunctionContext: AppFunctionContext, query: String): List<Note> {
        return repository.searchNotes(query).first()
    }

    /**
     * Retrieves a single note by its unique id.
     *
     * Use this when the user (or a prior function result) references a specific
     * note by id and wants its full contents.
     *
     * @param appFunctionContext Execution context supplied by the AppFunctions platform.
     * @param noteId The id of the note to retrieve.
     * @return The matching note, or null if no note with that id exists.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getNote(appFunctionContext: AppFunctionContext, noteId: Int): Note? {
        return repository.getNoteById(noteId)
    }

    /**
     * Deletes a note by its unique id.
     *
     * Use this when the user wants to remove, delete, or discard a specific note.
     *
     * @param appFunctionContext Execution context supplied by the AppFunctions platform.
     * @param noteId The id of the note to delete.
     * @return True if a note with that id existed and was deleted; false if no such note existed.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun deleteNote(appFunctionContext: AppFunctionContext, noteId: Int): Boolean {
        val existing = repository.getNoteById(noteId) ?: return false
        repository.deleteNote(existing.id)
        return true
    }
}
