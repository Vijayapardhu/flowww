package com.flowww.android.ui.viewmodel

import com.flowww.android.domain.models.FlowwwSettings
import com.flowww.android.domain.repository.SettingsRepository
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class FakeSettingsRepository : SettingsRepository {
        private val current = MutableStateFlow(FlowwwSettings())
        private val _settings = MutableStateFlow<Result<FlowwwSettings>>(Result.success(FlowwwSettings()))
        override val settings: Flow<Result<FlowwwSettings>> = _settings

        var failNextUpdate = false

        override suspend fun update(transform: (FlowwwSettings) -> FlowwwSettings): Result<Unit> {
            if (failNextUpdate) {
                failNextUpdate = false
                return Result.failure(RuntimeException("DataStore write failed"))
            }
            val updated = transform(current.value)
            current.value = updated
            _settings.value = Result.success(updated)
            return Result.success(Unit)
        }
    }

    @Test
    fun incomingApprovalCannotBeDisabledByPolicy() = runTest(dispatcher) {
        val repo = FakeSettingsRepository()
        val vm = SettingsViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.setIncomingApproval(false)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.settings.incomingApprovalRequired)
        assertTrue(
            vm.uiState.value.infoMessage?.contains("approval remains required") == true
        )
    }

    @Test
    fun sensitivityIsClampedToValidRange() = runTest(dispatcher) {
        val repo = FakeSettingsRepository()
        val vm = SettingsViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.setSensitivity(0.05f)
        advanceUntilIdle()
        assertEquals(0.3f, vm.uiState.value.settings.gestureSensitivity)

        vm.setSensitivity(1.5f)
        advanceUntilIdle()
        assertEquals(1.0f, vm.uiState.value.settings.gestureSensitivity)
    }

    @Test
    fun persistFailureSurfacesErrorMessage() = runTest(dispatcher) {
        val repo = FakeSettingsRepository()
        repo.failNextUpdate = true
        val vm = SettingsViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.setNotifications(false)
        advanceUntilIdle()

        // The repository's failure message must surface, not be swallowed.
        assertTrue(vm.uiState.value.errorMessage?.contains("DataStore write failed") == true)
    }

    @Test
    fun blankDeviceNameFallsBackToDefault() = runTest(dispatcher) {
        val repo = FakeSettingsRepository()
        val vm = SettingsViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.setDeviceName("   ")
        advanceUntilIdle()

        assertEquals(FlowwwSettings.DEFAULT_DEVICE_NAME, vm.uiState.value.settings.deviceDisplayName)
    }

    @Test
    fun togglesPersistThroughRepository() = runTest(dispatcher) {
        val repo = FakeSettingsRepository()
        val vm = SettingsViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.setAutoConnect(true)
        vm.setMotionGestures(true)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.settings.autoConnectLastDevice)
        assertTrue(vm.uiState.value.settings.motionGesturesEnabled)
        // The message states what the preference actually does in this build.
        assertTrue(
            vm.uiState.value.infoMessage?.contains("local staged content") == true
        )
    }
}
