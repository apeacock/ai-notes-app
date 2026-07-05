package com.ai.notes.ui.viewmodel

import com.ai.notes.data.ai.AppError
import com.ai.notes.data.ai.SummarizationRepository
import com.ai.notes.data.ai.SummarizeResult
import com.ai.notes.data.database.repositories.NoteRepository
import com.ai.notes.data.model.Note
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private fun note(n: Int) = Note(n, "Title $n", "Body $n", emptyList(), null, 0L, 0L)

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(notes: List<Note> = emptyList()): NotesViewModel {
        val noteRepository = mockk<NoteRepository>()
        every { noteRepository.getAllNotes() } returns MutableStateFlow(notes)
        every { noteRepository.searchNotes(any()) } returns MutableStateFlow(notes)
        val summarizationRepository = mockk<SummarizationRepository>()
        return NotesViewModel(noteRepository, summarizationRepository)
    }

    @Test
    fun `enterMultiSelectMode sets isMultiSelectMode true`() = runTest {
        val vm = buildViewModel()
        vm.enterMultiSelectMode()
        assertTrue(vm.isMultiSelectMode.value)
    }

    @Test
    fun `exitMultiSelectMode clears selection`() = runTest {
        val vm = buildViewModel(listOf(note(1)))
        vm.enterMultiSelectMode()
        vm.toggleSelection(1)
        vm.exitMultiSelectMode()
        assertFalse(vm.isMultiSelectMode.value)
        assertTrue(vm.selectedIds.value.isEmpty())
    }

    @Test
    fun `toggleSelection adds and removes id`() = runTest {
        val vm = buildViewModel(listOf(note(1)))
        vm.enterMultiSelectMode()
        vm.toggleSelection(1)
        assertEquals(setOf(1), vm.selectedIds.value)
        vm.toggleSelection(1)
        assertEquals(emptySet<Int>(), vm.selectedIds.value)
    }

    @Test
    fun `canSummarize false when fewer than 2 selected`() = runTest {
        val vm = buildViewModel(listOf(note(1)))
        vm.enterMultiSelectMode()
        vm.toggleSelection(1)
        assertFalse(vm.canSummarize())
    }

    @Test
    fun `canSummarize true when 2 to 10 selected`() = runTest {
        val vm = buildViewModel(listOf(note(1), note(2)))
        vm.enterMultiSelectMode()
        vm.toggleSelection(1)
        vm.toggleSelection(2)
        assertTrue(vm.canSummarize())
    }

    @Test
    fun `summarizeSelected with fewer than 2 sets EmptyBatch error`() = runTest {
        val vm = buildViewModel(listOf(note(1)))
        vm.enterMultiSelectMode()
        vm.toggleSelection(1)
        vm.summarizeSelected()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(AppError.EmptyBatch, vm.errorEvent.value)
    }

    @Test
    fun `summarizeSelected success populates summary state`() = runTest {
        val notes = listOf(note(1), note(2))
        val noteRepository = mockk<NoteRepository>()
        every { noteRepository.getAllNotes() } returns MutableStateFlow(notes)
        every { noteRepository.searchNotes(any()) } returns MutableStateFlow(notes)
        val summarizationRepository = mockk<SummarizationRepository>()
        coEvery { summarizationRepository.summarize(any()) } returns SummarizeResult.Success("Summary!")
        val vm = NotesViewModel(noteRepository, summarizationRepository)

        vm.enterMultiSelectMode()
        vm.toggleSelection(1)
        vm.toggleSelection(2)
        vm.summarizeSelected()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Summary!", vm.summary.value)
    }

    @Test
    fun `onSearchQueryChanged updates searchQuery state`() = runTest {
        val vm = buildViewModel()
        vm.onSearchQueryChanged("pasta")
        assertEquals("pasta", vm.searchQuery.value)
    }
}
