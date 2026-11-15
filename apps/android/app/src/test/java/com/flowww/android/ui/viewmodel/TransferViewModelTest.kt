package com.flowww.android.ui.viewmodel

import com.flowww.android.connection.TransferDirection
import com.flowww.android.connection.TransferRecord
import com.flowww.android.connection.TransferState
import com.flowww.android.connection.isTerminal
import com.flowww.android.domain.models.HistoryFilter
import com.flowww.android.domain.models.TransferFilter
import com.flowww.android.domain.repository.TransferRepository
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class TransferViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class FakeTransferRepository : TransferRepository {
        private val _transfers = MutableStateFlow<List<TransferRecord>>(emptyList())
        override val transfers: StateFlow<List<TransferRecord>> = _transfers

        /** cancel() fails honestly by default (service unavailable). */
        var cancelResult: Boolean = false

        fun add(record: TransferRecord) {
            _transfers.value = _transfers.value + record
        }

        override suspend fun refresh(): Result<Unit> = Result.success(Unit)
        override suspend fun cancel(transferId: String): Result<Boolean> = Result.success(cancelResult)
        override suspend fun recordTransfer(record: TransferRecord): Result<Unit> = runCatching {
            _transfers.value = _transfers.value + record
        }
    }

    private fun record(
        id: String,
        status: TransferState,
        direction: TransferDirection = TransferDirection.OUTGOING,
        createdAt: Long = System.currentTimeMillis(),
        size: Long = 1000L,
        bytesTransferred: Long = 0L
    ) = TransferRecord(
        id = id,
        sourceDeviceId = if (direction == TransferDirection.OUTGOING) "" else "src-dev",
        destinationDeviceId = if (direction == TransferDirection.OUTGOING) "dst-dev" else "",
        filename = "file-$id.bin",
        size = size,
        status = status,
        route = "direct",
        direction = direction,
        bytesTransferred = bytesTransferred,
        error = if (status == TransferState.FAILED) "boom" else null,
        createdAt = createdAt,
        completedAt = if (status.isTerminal()) createdAt + 100 else null,
        mimeType = "application/octet-stream"
    )

    private fun createVm(repo: FakeTransferRepository) = TransferViewModel(repo)

    @Test
    fun emptyTransfersShowHonestNoFabricatedState() = runTest(dispatcher) {
        val vm = createVm(FakeTransferRepository())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.historyState.collect { } }
        advanceUntilIdle()

        assertTrue(vm.uiState.value.transfers.isEmpty())
        assertTrue(
            vm.uiState.value.infoMessage?.contains("Rust") == true
                    || vm.uiState.value.infoMessage?.contains("not connected") == true
        )
        assertTrue(vm.historyState.value.infoMessage?.contains("not fabricated") == true)
    }

    @Test
    fun activeFilterExcludesTerminalRecords() = runTest(dispatcher) {
        val repo = FakeTransferRepository()
        repo.add(record("active-1", TransferState.TRANSFERRING, bytesTransferred = 500L))
        repo.add(record("done-1", TransferState.COMPLETED))
        repo.add(record("failed-1", TransferState.FAILED))
        val vm = TransferViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        advanceUntilIdle()

        // Default dashboard filter is ACTIVE.
        assertEquals(listOf("active-1"), vm.uiState.value.transfers.map { it.id })

        vm.setFilter(TransferFilter.ALL)
        advanceUntilIdle()
        assertEquals(3, vm.uiState.value.transfers.size)

        vm.setFilter(TransferFilter.COMPLETED)
        advanceUntilIdle()
        assertEquals(listOf("done-1"), vm.uiState.value.transfers.map { it.id })

        vm.setFilter(TransferFilter.FAILED)
        advanceUntilIdle()
        assertEquals(setOf("failed-1"), vm.uiState.value.transfers.map { it.id }.toSet())
    }

    @Test
    fun progressIsMappedFromBytesOverSize() = runTest(dispatcher) {
        val repo = FakeTransferRepository()
        repo.add(record("active-1", TransferState.TRANSFERRING, size = 1000L, bytesTransferred = 250L))
        val vm = TransferViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        advanceUntilIdle()

        assertEquals(0.25f, vm.uiState.value.transfers[0].progress, 0.001f)
        assertEquals("file-active-1.bin", vm.uiState.value.transfers[0].filename)
    }

    @Test
    fun historyFiltersByDirectionAndGroupsByDay() = runTest(dispatcher) {
        val today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val yesterday = today - 86_400_000L
        val repo = FakeTransferRepository()
        repo.add(record("out-today", TransferState.COMPLETED, direction = TransferDirection.OUTGOING, createdAt = today))
        repo.add(record("in-yesterday", TransferState.COMPLETED, direction = TransferDirection.INCOMING, createdAt = yesterday))
        val vm = TransferViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.historyState.collect { } }
        advanceUntilIdle()

        vm.setHistoryFilter(HistoryFilter.SENT)
        advanceUntilIdle()
        assertEquals(1, vm.historyState.value.totalCount)
        assertEquals("out-today", vm.historyState.value.groups.first().items[0].id)

        vm.setHistoryFilter(HistoryFilter.RECEIVED)
        advanceUntilIdle()
        assertEquals(1, vm.historyState.value.totalCount)
        assertEquals("in-yesterday", vm.historyState.value.groups.first().items[0].id)

        vm.setHistoryFilter(HistoryFilter.ALL)
        advanceUntilIdle()
        // Newest day first.
        assertEquals(2, vm.historyState.value.groups.size)
        assertEquals(LocalDate.now().toEpochDay(), vm.historyState.value.groups[0].dateEpochDay)
    }

    @Test
    fun findTransferReturnsMappedItemOrNull() = runTest(dispatcher) {
        val repo = FakeTransferRepository()
        repo.add(record("t-1", TransferState.TRANSFERRING, size = 800L, bytesTransferred = 200L))
        val vm = TransferViewModel(repo)
        advanceUntilIdle()

        val found = vm.findTransfer("t-1")
        assertTrue(found != null)
        assertEquals("t-1", found!!.id)
        assertEquals(0.25f, found.progress, 0.001f)
        assertNull(vm.findTransfer("missing"))
    }

    @Test
    fun cancelFailureSurfacesHonestUnavailableMessage() = runTest(dispatcher) {
        val repo = FakeTransferRepository() // cancelResult = false
        repo.add(record("t-1", TransferState.TRANSFERRING))
        val vm = TransferViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.cancel("t-1")
        advanceUntilIdle()

        assertEquals(
            "Cancel is unavailable until the Rust transfer engine is connected.",
            vm.uiState.value.errorMessage
        )
    }

    @Test
    fun retryUnavailableIsHonest() = runTest(dispatcher) {
        val vm = TransferViewModel(FakeTransferRepository())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.retryUnavailable()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.errorMessage?.contains("Retry is unavailable") == true)
    }
}
