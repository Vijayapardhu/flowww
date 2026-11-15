package com.flowww.android.ui.viewmodel

import com.flowww.android.domain.models.LocalOperation
import com.flowww.android.domain.models.LocalOperationStatus
import com.flowww.android.domain.models.LocalOperationType
import com.flowww.android.domain.models.OperationFilter
import com.flowww.android.domain.models.OperationSort
import com.flowww.android.domain.repository.OperationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The transfer manager and the operation history read the same real records.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class FakeOperationRepo(initial: List<LocalOperation> = emptyList()) : OperationRepository {
        private val backing = MutableStateFlow(initial)
        override val operations: StateFlow<List<LocalOperation>> = backing

        override suspend fun start(
            type: LocalOperationType,
            title: String,
            contentIds: List<String>,
            fileNames: List<String>,
            totalBytes: Long
        ): Result<LocalOperation> = runCatching {
            val operation = LocalOperation(
                id = "op-${backing.value.size + 1}",
                type = type,
                status = LocalOperationStatus.RUNNING,
                title = title,
                contentIds = contentIds,
                fileNames = fileNames,
                totalBytes = totalBytes,
                createdAt = System.currentTimeMillis()
            )
            backing.value = backing.value + operation
            operation
        }

        override suspend fun reportProgress(id: String, writtenBytes: Long): Result<Unit> = runCatching {
            backing.value = backing.value.map {
                if (it.id == id) it.copy(writtenBytes = writtenBytes) else it
            }
        }

        override suspend fun succeed(id: String, destination: String?): Result<Unit> = runCatching {
            backing.value = backing.value.map {
                if (it.id == id) {
                    it.copy(status = LocalOperationStatus.SUCCESS, destination = destination, writtenBytes = it.totalBytes)
                } else {
                    it
                }
            }
        }

        override suspend fun fail(id: String, message: String): Result<Unit> = runCatching {
            backing.value = backing.value.map {
                if (it.id == id) it.copy(status = LocalOperationStatus.FAILED, errorMessage = message) else it
            }
        }

        override suspend fun cancel(id: String): Result<Unit> = runCatching {
            backing.value = backing.value.map {
                if (it.id == id) it.copy(status = LocalOperationStatus.CANCELLED) else it
            }
        }

        override suspend fun clear(): Result<Int> = runCatching {
            val count = backing.value.size
            backing.value = emptyList()
            count
        }

        override suspend fun find(id: String): LocalOperation? = backing.value.firstOrNull { it.id == id }
        override suspend fun statusOf(id: String): LocalOperationStatus? = find(id)?.status
    }

    private fun operation(
        id: String,
        status: LocalOperationStatus = LocalOperationStatus.SUCCESS,
        type: LocalOperationType = LocalOperationType.LOCAL_EXPORT,
        title: String = "1 item · 1 KB",
        destination: String? = "Downloads",
        createdAt: Long = System.currentTimeMillis(),
        size: Long = 1024L
    ) = LocalOperation(
        id = id,
        type = type,
        status = status,
        title = title,
        contentIds = listOf(id),
        fileNames = listOf("file-$id.bin"),
        totalBytes = size,
        writtenBytes = if (status == LocalOperationStatus.SUCCESS) size else 0L,
        destination = destination,
        createdAt = createdAt,
        finishedAt = if (status == LocalOperationStatus.RUNNING) null else createdAt + 10
    )

    private fun createVm(repo: OperationRepository) = TransferViewModel(repo)

    @Test
    fun managerListsTheRealOperations() = runTest(dispatcher) {
        val repo = FakeOperationRepo(listOf(operation("a"), operation("b")))
        val vm = createVm(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.historyState.collect { } }
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.operations.size)
        assertEquals(2, vm.uiState.value.allOperations.size)
    }

    @Test
    fun managerCountsRunningOperations() = runTest(dispatcher) {
        val repo = FakeOperationRepo(
            listOf(
                operation("a", status = LocalOperationStatus.RUNNING),
                operation("b", status = LocalOperationStatus.SUCCESS)
            )
        )
        val vm = createVm(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.historyState.collect { } }
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.runningCount)
    }

    @Test
    fun managerFiltersByStatus() = runTest(dispatcher) {
        val repo = FakeOperationRepo(
            listOf(
                operation("a", status = LocalOperationStatus.SUCCESS),
                operation("b", status = LocalOperationStatus.FAILED),
                operation("c", status = LocalOperationStatus.CANCELLED)
            )
        )
        val vm = createVm(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.historyState.collect { } }
        advanceUntilIdle()

        vm.setFilter(OperationFilter.FAILED)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.operations.size)
        assertEquals("b", vm.uiState.value.operations.first().id)

        vm.setFilter(OperationFilter.CANCELLED)
        advanceUntilIdle()
        assertEquals("c", vm.uiState.value.operations.first().id)

        vm.setFilter(OperationFilter.ALL)
        advanceUntilIdle()
        assertEquals(3, vm.uiState.value.operations.size)
    }

    @Test
    fun historySearchMatchesTitleTypeAndDestination() = runTest(dispatcher) {
        val repo = FakeOperationRepo(
            listOf(
                operation("a", title = "invoice.pdf", destination = "Downloads"),
                operation("b", title = "notes.txt", destination = "Chrome")
            )
        )
        val vm = createVm(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.historyState.collect { } }
        advanceUntilIdle()

        vm.setQuery("invoice")
        advanceUntilIdle()
        assertEquals(1, vm.historyState.value.operations.size)

        vm.setQuery("Chrome")
        advanceUntilIdle()
        assertEquals("b", vm.historyState.value.operations.first().id)

        vm.clearQuery()
        advanceUntilIdle()
        assertEquals(2, vm.historyState.value.operations.size)
    }

    @Test
    fun historySortsByTheChosenOrder() = runTest(dispatcher) {
        val repo = FakeOperationRepo(
            listOf(
                operation("a", title = "bravo", createdAt = 100L),
                operation("b", title = "alpha", createdAt = 200L)
            )
        )
        val vm = createVm(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.historyState.collect { } }
        advanceUntilIdle()

        vm.setSort(OperationSort.NAME)
        advanceUntilIdle()
        assertEquals("alpha", vm.historyState.value.operations.first().title)

        vm.setSort(OperationSort.OLDEST)
        advanceUntilIdle()
        assertEquals("bravo", vm.historyState.value.operations.first().title)
    }

    @Test
    fun historyGroupsOperationsByDay() = runTest(dispatcher) {
        val now = System.currentTimeMillis()
        val yesterday = now - 24 * 60 * 60 * 1000
        val repo = FakeOperationRepo(
            listOf(
                operation("a", createdAt = now),
                operation("b", createdAt = now),
                operation("c", createdAt = yesterday)
            )
        )
        val vm = createVm(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.historyState.collect { } }
        advanceUntilIdle()

        assertEquals(2, vm.historyState.value.groups.size)
        val today = LocalDate.now().toEpochDay()
        assertEquals(today, vm.historyState.value.groups.first().dateEpochDay)
        assertEquals(2, vm.historyState.value.groups.first().items.size)
    }

    @Test
    fun cancellingARunningOperationMarksItCancelled() = runTest(dispatcher) {
        val repo = FakeOperationRepo(listOf(operation("a", status = LocalOperationStatus.RUNNING)))
        val vm = createVm(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.historyState.collect { } }
        advanceUntilIdle()

        vm.cancelOperation("a")
        advanceUntilIdle()

        assertEquals(LocalOperationStatus.CANCELLED, repo.find("a")?.status)
        assertEquals("Operation cancelled.", vm.uiState.value.infoMessage)
    }

    @Test
    fun clearingHistoryRemovesEveryRecord() = runTest(dispatcher) {
        val repo = FakeOperationRepo(listOf(operation("a"), operation("b")))
        val vm = createVm(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.historyState.collect { } }
        advanceUntilIdle()

        vm.clearHistory()
        advanceUntilIdle()

        assertTrue(repo.operations.value.isEmpty())
        assertEquals("Cleared 2 record(s).", vm.uiState.value.infoMessage)
    }

    @Test
    fun operationDetailsCanBeLoadedById() = runTest(dispatcher) {
        val repo = FakeOperationRepo(listOf(operation("a", destination = "Downloads")))
        val vm = createVm(repo)

        val found = vm.findOperation("a")
        assertNotNull(found)
        assertEquals("Downloads", found!!.destination)
        assertEquals("Downloads", found.destination)
        assertNull(vm.findOperation("missing"))
    }

    @Test
    fun historyNeverContainsFabricatedRecords() = runTest(dispatcher) {
        val repo = FakeOperationRepo(emptyList())
        val vm = createVm(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.historyState.collect { } }
        advanceUntilIdle()

        assertTrue(vm.historyState.value.operations.isEmpty())
        assertEquals(0, vm.historyState.value.totalCount)
        assertTrue(vm.historyState.value.groups.isEmpty())
    }
}
