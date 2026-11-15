package com.flowww.android.ui.viewmodel

import com.flowww.android.FlowwwError
import com.flowww.android.content.ContentLifecycleState
import com.flowww.android.content.ContentType
import com.flowww.android.content.StagedContent
import com.flowww.android.domain.models.ContentKind
import com.flowww.android.domain.models.LocalContentHandle
import com.flowww.android.domain.models.LocalOperation
import com.flowww.android.domain.models.LocalOperationStatus
import com.flowww.android.domain.models.LocalOperationType
import com.flowww.android.domain.models.LocalTrustedDevice
import com.flowww.android.domain.models.StagedSortOrder
import com.flowww.android.domain.models.StagedTypeFilter
import com.flowww.android.domain.repository.ContentShareBuilder
import com.flowww.android.domain.repository.LocalContentGateway
import com.flowww.android.domain.repository.OperationRepository
import com.flowww.android.domain.repository.StagedContentRepository
import com.flowww.android.domain.repository.StagedPreview
import com.flowww.android.domain.repository.TrustedDeviceRepository
import com.flowww.android.transfer.LocalContentHandoff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class StagedContentViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun item(
        id: String,
        contentType: ContentType = ContentType.TEXT,
        contentReference: String = "payload-$id",
        name: String = "item-$id",
        size: Long = 10L,
        createdAt: Long = System.currentTimeMillis()
    ) = StagedContent(
        contentId = id,
        contentType = contentType,
        lifecycleState = ContentLifecycleState.STAGED,
        displayName = name,
        mimeType = "text/plain",
        size = size,
        sourceApplication = null,
        createdAt = createdAt,
        expiresAt = Long.MAX_VALUE,
        contentReference = contentReference,
        authorizationContext = null
    )

    private class FakeStagedRepo(
        initial: List<StagedContent> = emptyList()
    ) : StagedContentRepository {
        private val backing = MutableStateFlow(initial)
        override val items: StateFlow<List<StagedContent>> = backing

        /** Fails with ProviderUnavailable by default — send must fail honestly. */
        var sendResult: Result<Unit> = Result.failure(FlowwwError.ProviderUnavailable("rust-transfer"))

        override suspend fun refresh(): Result<Unit> = Result.success(Unit)
        override suspend fun remove(contentId: String): Result<Unit> = runCatching {
            backing.value = backing.value.filterNot { it.contentId == contentId }
        }
        override suspend fun removeMany(contentIds: Set<String>): Result<Int> = runCatching {
            val before = backing.value.size
            backing.value = backing.value.filterNot { it.contentId in contentIds }
            before - backing.value.size
        }
        override suspend fun clearExpired(): Result<Int> = Result.success(0)
        override suspend fun cleanupOrphans(): Result<Int> = Result.success(0)
        override suspend fun storageUsage(): Result<Long> =
            Result.success(backing.value.sumOf { it.size ?: 0L })
        override suspend fun preview(contentId: String): Result<StagedPreview> =
            backing.value.firstOrNull { it.contentId == contentId }
                ?.let { Result.success(StagedPreview.Text(it.contentReference, it.mimeType)) }
                ?: Result.failure(FlowwwError.ContentNotFound(contentId))
        override suspend fun send(contentIds: Set<String>, destinationDeviceId: String): Result<Unit> = sendResult
    }

    private class FakeTrustedRepo(
        initial: List<LocalTrustedDevice> = emptyList()
    ) : TrustedDeviceRepository {
        private val devices = MutableStateFlow(initial)
        override fun trustedDevices(): Flow<List<LocalTrustedDevice>> = devices
        override suspend fun refresh(): Result<List<LocalTrustedDevice>> = Result.success(devices.value)
        override suspend fun add(record: LocalTrustedDevice): Result<Unit> = runCatching {
            devices.value = devices.value + record
        }
        override suspend fun forget(deviceId: String): Result<Unit> = runCatching {
            devices.value = devices.value.filterNot { it.deviceId == deviceId }
        }
        override suspend fun rename(deviceId: String, displayName: String): Result<Unit> = runCatching {
            devices.value = devices.value.map {
                if (it.deviceId == deviceId) it.copy(displayName = displayName) else it
            }
        }
    }

    private class FakeGateway(
        private val items: Map<String, LocalContentHandle> = emptyMap()
    ) : LocalContentGateway {
        override suspend fun describe(contentId: String): Result<LocalContentHandle> =
            items[contentId]?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("Staged item is no longer available"))

        override suspend fun describeAll(contentIds: List<String>): Result<List<LocalContentHandle>> =
            runCatching { contentIds.map { describe(it).getOrThrow() } }

        override suspend fun openStream(contentId: String): Result<InputStream> =
            Result.success("payload".byteInputStream())
    }

    private class FakeOperationRepo : OperationRepository {
        private val backing = MutableStateFlow<List<LocalOperation>>(emptyList())
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
                if (it.id == id) it.copy(status = LocalOperationStatus.SUCCESS, destination = destination) else it
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

    private fun handle(id: String, name: String, text: String = "payload-$id") = LocalContentHandle(
        contentId = id,
        displayName = name,
        mimeType = "text/plain",
        size = 10L,
        kind = ContentKind.TEXT,
        text = text
    )

    /** Records what was handed to the share builder; no Android runtime needed. */
    private class FakeShareBuilder : ContentShareBuilder {
        val requested = mutableListOf<List<LocalContentHandle>>()
        override fun build(handles: List<LocalContentHandle>): Result<Pair<android.content.Intent, String>> {
            requested += handles
            return Result.success(
                android.content.Intent("android.intent.action.SEND") to
                    (if (handles.size == 1) "Share ${handles.first().displayName}" else "Share ${handles.size} items")
            )
        }
    }

    private fun createVm(
        stagedRepo: StagedContentRepository,
        trustedRepo: TrustedDeviceRepository = FakeTrustedRepo(),
        gateway: LocalContentGateway = FakeGateway(),
        operations: OperationRepository = FakeOperationRepo(),
        shareBuilder: ContentShareBuilder = FakeShareBuilder()
    ): StagedContentViewModel {
        val handoff = LocalContentHandoff(gateway) { throw IllegalStateException("no context in unit tests") }
        return StagedContentViewModel(stagedRepo, trustedRepo, gateway, handoff, operations, shareBuilder)
    }

    @Test
    fun selectionToggleAddsAndRemovesIds() = runTest(dispatcher) {
        val repo = FakeStagedRepo(listOf(item("a"), item("b")))
        val vm = createVm(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.toggleSelection("a")
        vm.toggleSelection("b")
        advanceUntilIdle()
        assertEquals(setOf("a", "b"), vm.uiState.value.selectedIds)

        vm.toggleSelection("a")
        advanceUntilIdle()
        assertEquals(setOf("b"), vm.uiState.value.selectedIds)
    }

    @Test
    fun selectAllAndClearSelectionDriveTheToolbar() = runTest(dispatcher) {
        val repo = FakeStagedRepo(listOf(item("a"), item("b"), item("c")))
        val vm = createVm(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.selectAll()
        advanceUntilIdle()
        assertEquals(3, vm.uiState.value.selectedIds.size)
        assertTrue(vm.uiState.value.allSelected)

        vm.clearSelection()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.selectedIds.isEmpty())
    }

    @Test
    fun removeSelectedRemovesTheItemsAndReportsTheCount() = runTest(dispatcher) {
        val repo = FakeStagedRepo(listOf(item("a"), item("b")))
        val vm = createVm(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.selectAll()
        advanceUntilIdle()
        vm.removeSelected()
        advanceUntilIdle()

        assertTrue(repo.items.value.isEmpty())
        assertEquals("Removed 2 item(s)", vm.uiState.value.infoMessage)
    }

    @Test
    fun searchFiltersTheVisibleItems() = runTest(dispatcher) {
        val repo = FakeStagedRepo(listOf(item("a", name = "invoice.pdf"), item("b", name = "notes.txt")))
        val vm = createVm(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.setQuery("invoice")
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.items.size)
        assertEquals("invoice.pdf", vm.uiState.value.items.first().name)
        assertEquals(2, vm.uiState.value.allItems.size)
        assertTrue(vm.uiState.value.isFiltered)
    }

    @Test
    fun typeFilterSelectsOnlyThatContentType() = runTest(dispatcher) {
        val repo = FakeStagedRepo(
            listOf(
                item("t", contentType = ContentType.TEXT),
                item("u", contentType = ContentType.URL),
                item("i", contentType = ContentType.IMAGE)
            )
        )
        val vm = createVm(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.setTypeFilter(StagedTypeFilter.URL)
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.items.size)
        assertEquals(ContentType.URL, vm.uiState.value.items.first().contentType)
    }

    @Test
    fun sortOrderCanBeChanged() = runTest(dispatcher) {
        val repo = FakeStagedRepo(
            listOf(
                item("a", name = "bravo", createdAt = 100L),
                item("b", name = "alpha", createdAt = 200L)
            )
        )
        val vm = createVm(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.setSortOrder(StagedSortOrder.NAME)
        advanceUntilIdle()
        assertEquals("alpha", vm.uiState.value.items.first().name)

        vm.setSortOrder(StagedSortOrder.OLDEST)
        advanceUntilIdle()
        assertEquals("bravo", vm.uiState.value.items.first().name)

        vm.setSortOrder(StagedSortOrder.NEWEST)
        advanceUntilIdle()
        assertEquals("alpha", vm.uiState.value.items.first().name)
    }

    @Test
    fun clearFiltersRestoresEveryItem() = runTest(dispatcher) {
        val repo = FakeStagedRepo(listOf(item("a", name = "invoice.pdf"), item("b", name = "notes.txt")))
        val vm = createVm(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.setQuery("invoice")
        vm.setTypeFilter(StagedTypeFilter.TEXT)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.items.size)

        vm.clearFilters()
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.items.size)
        assertFalse(vm.uiState.value.isFiltered)
    }

    @Test
    fun sendFailsHonestlyBecauseNetworkTransferIsUnavailable() = runTest(dispatcher) {
        val repo = FakeStagedRepo(listOf(item("a")))
        val vm = createVm(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.selectAll()
        advanceUntilIdle()
        vm.sendSelected("device-1")
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.errorMessage)
        assertTrue(vm.uiState.value.errorMessage!!.contains("Network transfer is unavailable"))
    }

    @Test
    fun shareIntentIsBuiltFromTheSelection() = runTest(dispatcher) {
        val repo = FakeStagedRepo(listOf(item("a"), item("b")))
        val gateway = FakeGateway(mapOf("a" to handle("a", "alpha"), "b" to handle("b", "beta")))
        val builder = FakeShareBuilder()
        val vm = createVm(repo, gateway = gateway, shareBuilder = builder)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.selectAll()
        advanceUntilIdle()

        val result = vm.prepareShare()
        assertTrue("share should be prepared: $result", result.isSuccess)
        assertEquals("Share 2 items", result.getOrNull()?.second)
        assertEquals(1, builder.requested.size)
        assertEquals(2, builder.requested.first().size)
    }

    @Test
    fun previewIsUnavailableForAMissingItem() = runTest(dispatcher) {
        val repo = FakeStagedRepo(listOf(item("a")))
        val vm = createVm(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.openPreview("missing")
        advanceUntilIdle()

        assertEquals(null, vm.uiState.value.preview)
        assertNotNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun previewShowsTheInlineTextOfAnItem() = runTest(dispatcher) {
        val repo = FakeStagedRepo(listOf(item("a", contentReference = "hello world")))
        val gateway = FakeGateway(mapOf("a" to handle("a", "alpha", "hello world")))
        val vm = createVm(repo, gateway = gateway)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.openPreview("a")
        advanceUntilIdle()

        val preview = vm.uiState.value.preview
        assertNotNull(preview)
        assertTrue(preview is com.flowww.android.domain.models.StagedPreviewUi.Text)
        assertEquals("hello world", (preview as com.flowww.android.domain.models.StagedPreviewUi.Text).body)
    }

    @Test
    fun storageUsageIsReportedFromTheRepository() = runTest(dispatcher) {
        val repo = FakeStagedRepo(listOf(item("a", size = 100L), item("b", size = 50L)))
        val vm = createVm(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.refresh()
        advanceUntilIdle()

        assertEquals(150L, vm.uiState.value.storageUsageBytes)
    }
}
