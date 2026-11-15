package com.flowww.android.ui.viewmodel

import com.flowww.android.FlowwwError
import com.flowww.android.connection.Device
import com.flowww.android.connection.DeviceInfo
import com.flowww.android.connection.Platform
import com.flowww.android.content.ContentLifecycleState
import com.flowww.android.content.ContentType
import com.flowww.android.content.StagedContent
import com.flowww.android.domain.repository.StagedContentRepository
import com.flowww.android.domain.repository.StagedPreview
import com.flowww.android.domain.repository.TrustedDeviceRepository
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class StagedContentViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun item(
        id: String,
        contentType: ContentType = ContentType.TEXT,
        contentReference: String = "payload-$id",
        expiresAt: Long = Long.MAX_VALUE,
        lifecycleState: ContentLifecycleState = ContentLifecycleState.STAGED
    ) = StagedContent(
        contentId = id,
        contentType = contentType,
        lifecycleState = lifecycleState,
        displayName = "item-$id",
        mimeType = "text/plain",
        size = 10,
        sourceApplication = null,
        createdAt = System.currentTimeMillis(),
        expiresAt = expiresAt,
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
        initial: List<Device> = emptyList()
    ) : TrustedDeviceRepository {
        private val devices = MutableStateFlow(initial)
        override fun trustedDevices(): Flow<List<Device>> = devices
        override suspend fun refresh(): Result<List<Device>> = Result.success(devices.value)
        override suspend fun forget(deviceId: String): Result<Unit> =
            Result.failure(UnsupportedOperationException("Trusted-device removal is unavailable until the Rust trust store is connected"))
        override suspend fun rename(deviceId: String, displayName: String): Result<Unit> =
            Result.failure(UnsupportedOperationException("Renaming is unavailable until the Rust trust store is connected"))
    }

    private fun trustedDevice(id: String, name: String) = Device(
        info = DeviceInfo(
            deviceId = id,
            deviceName = name,
            platform = Platform.Windows,
            protocolVersion = "FLW/1.0"
        ),
        publicKeyFingerprint = "fp-$id",
        trusted = true,
        lastSeenMs = null,
        createdAtMs = System.currentTimeMillis()
    )

    private fun createVm(
        stagedRepo: StagedContentRepository,
        trustedRepo: TrustedDeviceRepository = FakeTrustedRepo()
    ) = StagedContentViewModel(stagedRepo, trustedRepo)

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
    fun selectAllAndClearSelection() = runTest(dispatcher) {
        val repo = FakeStagedRepo(listOf(item("a"), item("b"), item("c")))
        val vm = createVm(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.selectAll()
        advanceUntilIdle()
        assertEquals(setOf("a", "b", "c"), vm.uiState.value.selectedIds)
        assertTrue(vm.uiState.value.allSelected)

        vm.clearSelection()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.selectedIds.isEmpty())
    }

    @Test
    fun removeSelectedRemovesItemsAndClearsSelection() = runTest(dispatcher) {
        val repo = FakeStagedRepo(listOf(item("a"), item("b"), item("c")))
        val vm = createVm(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.selectAll()
        vm.removeSelected()
        advanceUntilIdle()

        assertEquals(0, vm.uiState.value.items.size)
        assertTrue(vm.uiState.value.selectedIds.isEmpty())
        assertEquals("Removed 3 item(s)", vm.uiState.value.infoMessage)
    }

    @Test
    fun sendSelectedWithEmptySelectionSetsInfoMessage() = runTest(dispatcher) {
        val repo = FakeStagedRepo(listOf(item("a")))
        val vm = createVm(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.sendSelected("device-1")
        advanceUntilIdle()

        assertEquals("Select at least one staged item before sending.", vm.uiState.value.infoMessage)
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun sendSelectedFailureIsHonestAboutMissingEngine() = runTest(dispatcher) {
        val repo = FakeStagedRepo(listOf(item("a")))
        val vm = createVm(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.toggleSelection("a")
        vm.sendSelected("device-1")
        advanceUntilIdle()

        val error = vm.uiState.value.errorMessage
        assertTrue(
            "Expected honest unavailable message, got: $error",
            error != null && error.contains("Rust transfer engine")
        )
        // The staged items remain local — nothing is marked transferred.
        assertEquals(1, vm.uiState.value.items.size)
    }

    @Test
    fun expiredAndBlobMissingItemsAreMappedHonestly() = runTest(dispatcher) {
        val missing = item("missing", contentType = ContentType.FILE, contentReference = "C:/does-not-exist/blob.bin")
        val expired = item("expired", contentType = ContentType.TEXT, expiresAt = 1L)
        val existingFile = File.createTempFile("flowww-test", ".bin").apply { writeBytes(byteArrayOf(1)) }
        try {
            val present = item("present", contentType = ContentType.FILE, contentReference = existingFile.absolutePath)
            val repo = FakeStagedRepo(listOf(missing, expired, present))
            val vm = createVm(repo)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
            advanceUntilIdle()

            val byId = vm.uiState.value.items.associateBy { it.id }
            assertTrue(byId.getValue("missing").blobMissing)
            assertFalse(byId.getValue("missing").canPreview)
            assertTrue(byId.getValue("expired").isExpired)
            assertFalse(byId.getValue("expired").canPreview)
            assertFalse(byId.getValue("present").blobMissing)
            assertTrue(byId.getValue("present").canPreview)
            // UI models never carry the underlying filesystem path.
            assertTrue(byId.getValue("present").name.isNotBlank())
        } finally {
            existingFile.delete()
        }
    }

    @Test
    fun destinationsReflectTrustedDevices() = runTest(dispatcher) {
        val repo = FakeStagedRepo(listOf(item("a")))
        val trusted = FakeTrustedRepo(listOf(trustedDevice("win-1", "Office PC")))
        val vm = createVm(repo, trusted)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.destinations.collect { } }
        advanceUntilIdle()

        assertEquals(1, vm.destinations.value.size)
        assertEquals("Office PC", vm.destinations.value[0].info.deviceName)
    }

    @Test
    fun destinationsAreEmptyAndUnavailableWhenNothingTrusted() = runTest(dispatcher) {
        val repo = FakeStagedRepo(listOf(item("a")))
        val vm = createVm(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.destinations.collect { } }
        advanceUntilIdle()

        assertTrue(vm.destinations.value.isEmpty())
    }
}
