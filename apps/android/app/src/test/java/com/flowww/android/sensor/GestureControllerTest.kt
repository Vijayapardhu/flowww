package com.flowww.android.sensor

import com.flowww.android.connection.*
import com.flowww.android.content.ContentLifecycleState
import com.flowww.android.content.StagedContent
import com.flowww.android.storage.StagedStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GestureControllerTest {

    private fun makeStagedContent(
        contentId: String = "content_1",
        state: ContentLifecycleState = ContentLifecycleState.STAGED
    ): StagedContent {
        return StagedContent(
            contentId = contentId,
            contentType = com.flowww.android.content.ContentType.URL,
            lifecycleState = state,
            displayName = "Test URL",
            mimeType = "text/uri-list",
            size = 100L,
            sourceApplication = "browser",
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 300_000L,
            contentReference = "ref_1",
            authorizationContext = null
        )
    }

    private fun makeController(
        stagedStore: StagedStore,
        connectionService: ConnectionService
    ): GestureController {
        return GestureController(
            connectionService = connectionService,
            stagedStore = stagedStore,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onResult = {}
        )
    }

    @Test
    fn controller_disarmed_ignores_events() = runBlocking {
        val store = StagedStore(java.io.File(java.lang.System.getProperty("java.io.tmpdir"), "gesture_ctrl_test"))
        val conn = object : ConnectionService {
            override suspend fun getLocalDeviceInfo(): DeviceInfo = DeviceInfo("d1", "D", Platform.Windows, "FLW/1.0")
            override suspend fun getTrustedDevices(): List<Device> = emptyList()
            override suspend fun discoverPeers(): List<DiscoveredPeer> = emptyList()
            override suspend fun connectToDevice(deviceId: String): Boolean = true
            override suspend fun disconnectDevice(deviceId: String) {}
            override suspend fun isDeviceConnected(deviceId: String): Boolean = false
            override suspend fun initiateSend(deviceId: String, contentId: String): TransferInitiationResult =
                TransferInitiationResult("t1", TransferState.CONNECTING)
            override suspend fun cancelTransfer(transferId: String): Boolean = true
            override suspend fun getTransferStatus(transferId: String): TransferRecord? = null
            override suspend fun getActiveTransfers(): List<TransferRecord> = emptyList()
            override suspend fun shutdown() {}
        }
        val controller = makeController(store, conn)
        // Not armed

        val event = GestureEvent.Send(
            timestamp = System.currentTimeMillis(),
            confidence = 0.9f,
            calibratedY = 10.0f,
            metrics = GestureMetrics(200, 10.0f, 0.5f, 1.0f, 0f, true)
        )

        var result: GestureResult? = null
        val capturingController = GestureController(
            connectionService = conn,
            stagedStore = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onResult = { result = it }
        )
        capturingController.handleEvent(event)

        assertTrue(result is GestureResult.Ignored)
    }

    @Test
    fn controller_send_with_no_content_is_rejected() = runBlocking {
        val store = StagedStore(java.io.File(java.lang.System.getProperty("java.io.tmpdir"), "gesture_ctrl_test2"))
        val conn = object : ConnectionService {
            override suspend fun getLocalDeviceInfo(): DeviceInfo = DeviceInfo("d1", "D", Platform.Windows, "FLW/1.0")
            override suspend fun getTrustedDevices(): List<Device> = emptyList()
            override suspend fun discoverPeers(): List<DiscoveredPeer> = emptyList()
            override suspend fun connectToDevice(deviceId: String): Boolean = true
            override suspend fun disconnectDevice(deviceId: String) {}
            override suspend fun isDeviceConnected(deviceId: String): Boolean = false
            override suspend fun initiateSend(deviceId: String, contentId: String): TransferInitiationResult =
                TransferInitiationResult("t1", TransferState.CONNECTING)
            override suspend fun cancelTransfer(transferId: String): Boolean = true
            override suspend fun getTransferStatus(transferId: String): TransferRecord? = null
            override suspend fun getActiveTransfers(): List<TransferRecord> = emptyList()
            override suspend fun shutdown() {}
        }
        val controller = makeController(store, conn)
        controller.arm()
        controller.selectTarget("device_1")

        var result: GestureResult? = null
        val capturingController = GestureController(
            connectionService = conn,
            stagedStore = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onResult = { result = it }
        )
        capturingController.arm()
        capturingController.selectTarget("device_1")

        val event = GestureEvent.Send(
            timestamp = System.currentTimeMillis(),
            confidence = 0.9f,
            calibratedY = 10.0f,
            metrics = GestureMetrics(200, 10.0f, 0.5f, 1.0f, 0f, true)
        )
        capturingController.handleEvent(event)

        assertTrue(result is GestureResult.Rejected)
        val rejected = result as GestureResult.Rejected
        assertTrue(rejected.reason.contains("No staged content"))
    }

    @Test
    fn controller_send_with_no_target_is_rejected() = runBlocking {
        val store = StagedStore(java.io.File(java.lang.System.getProperty("java.io.tmpdir"), "gesture_ctrl_test3"))
        // Insert staged content
        runBlocking {
            store.insert(makeStagedContent())
        }
        val conn = object : ConnectionService {
            override suspend fun getLocalDeviceInfo(): DeviceInfo = DeviceInfo("d1", "D", Platform.Windows, "FLW/1.0")
            override suspend fun getTrustedDevices(): List<Device> = emptyList()
            override suspend fun discoverPeers(): List<DiscoveredPeer> = emptyList()
            override suspend fun connectToDevice(deviceId: String): Boolean = true
            override suspend fun disconnectDevice(deviceId: String) {}
            override suspend fun isDeviceConnected(deviceId: String): Boolean = false
            override suspend fun initiateSend(deviceId: String, contentId: String): TransferInitiationResult =
                TransferInitiationResult("t1", TransferState.CONNECTING)
            override suspend fun cancelTransfer(transferId: String): Boolean = true
            override suspend fun getTransferStatus(transferId: String): TransferRecord? = null
            override suspend fun getActiveTransfers(): List<TransferRecord> = emptyList()
            override suspend fun shutdown() {}
        }
        val controller = makeController(store, conn)
        controller.arm()
        // No target selected

        var result: GestureResult? = null
        val capturingController = GestureController(
            connectionService = conn,
            stagedStore = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onResult = { result = it }
        )
        capturingController.arm()

        val event = GestureEvent.Send(
            timestamp = System.currentTimeMillis(),
            confidence = 0.9f,
            calibratedY = 10.0f,
            metrics = GestureMetrics(200, 10.0f, 0.5f, 1.0f, 0f, true)
        )
        capturingController.handleEvent(event)

        assertTrue(result is GestureResult.Rejected)
        val rejected = result as GestureResult.Rejected
        assertTrue(rejected.reason.contains("No trusted target"))
    }

    @Test
    fn controller_send_success_is_accepted() = runBlocking {
        val store = StagedStore(java.io.File(java.lang.System.getProperty("java.io.tmpdir"), "gesture_ctrl_test4"))
        runBlocking {
            store.insert(makeStagedContent())
        }
        var sendCalled = false
        val conn = object : ConnectionService {
            override suspend fun getLocalDeviceInfo(): DeviceInfo = DeviceInfo("d1", "D", Platform.Windows, "FLW/1.0")
            override suspend fun getTrustedDevices(): List<Device> = emptyList()
            override suspend fun discoverPeers(): List<DiscoveredPeer> = emptyList()
            override suspend fun connectToDevice(deviceId: String): Boolean = true
            override suspend fun disconnectDevice(deviceId: String) {}
            override suspend fun isDeviceConnected(deviceId: String): Boolean = false
            override suspend fun initiateSend(deviceId: String, contentId: String): TransferInitiationResult {
                sendCalled = true
                return TransferInitiationResult("t1", TransferState.CONNECTING)
            }
            override suspend fun cancelTransfer(transferId: String): Boolean = true
            override suspend fun getTransferStatus(transferId: String): TransferRecord? = null
            override suspend fun getActiveTransfers(): List<TransferRecord> = emptyList()
            override suspend fun shutdown() {}
        }
        val controller = makeController(store, conn)
        controller.arm()
        controller.selectTarget("device_1")

        var result: GestureResult? = null
        val capturingController = GestureController(
            connectionService = conn,
            stagedStore = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onResult = { result = it }
        )
        capturingController.arm()
        capturingController.selectTarget("device_1")

        val event = GestureEvent.Send(
            timestamp = System.currentTimeMillis(),
            confidence = 0.9f,
            calibratedY = 10.0f,
            metrics = GestureMetrics(200, 10.0f, 0.5f, 1.0f, 0f, true)
        )
        capturingController.handleEvent(event)

        assertTrue(sendCalled)
        assertTrue(result is GestureResult.Accepted)
    }

    @Test
    fn controller_cancel_without_active_transfer_is_rejected() = runBlocking {
        val store = StagedStore(java.io.File(java.lang.System.getProperty("java.io.tmpdir"), "gesture_ctrl_test5"))
        val conn = object : ConnectionService {
            override suspend fun getLocalDeviceInfo(): DeviceInfo = DeviceInfo("d1", "D", Platform.Windows, "FLW/1.0")
            override suspend fun getTrustedDevices(): List<Device> = emptyList()
            override suspend fun discoverPeers(): List<DiscoveredPeer> = emptyList()
            override suspend fun connectToDevice(deviceId: String): Boolean = true
            override suspend fun disconnectDevice(deviceId: String) {}
            override suspend fun isDeviceConnected(deviceId: String): Boolean = false
            override suspend fun initiateSend(deviceId: String, contentId: String): TransferInitiationResult =
                TransferInitiationResult("t1", TransferState.CONNECTING)
            override suspend fun cancelTransfer(transferId: String): Boolean = true
            override suspend fun getTransferStatus(transferId: String): TransferRecord? = null
            override suspend fun getActiveTransfers(): List<TransferRecord> = emptyList()
            override suspend fun shutdown() {}
        }
        val controller = makeController(store, conn)
        controller.arm()
        controller.selectTarget("device_1")
        // No active transfer set

        var result: GestureResult? = null
        val capturingController = GestureController(
            connectionService = conn,
            stagedStore = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onResult = { result = it }
        )
        capturingController.arm()
        capturingController.selectTarget("device_1")

        val event = GestureEvent.Cancel(
            timestamp = System.currentTimeMillis(),
            confidence = 0.9f,
            rotationMagnitude = 5.0f,
            metrics = GestureMetrics(200, 1.0f, 0.5f, 1.0f, 5.0f, true)
        )
        capturingController.handleEvent(event)

        assertTrue(result is GestureResult.Rejected)
        val rejected = result as GestureResult.Rejected
        assertTrue(rejected.reason.contains("No active transfer"))
    }

    @Test
    fn controller_low_confidence_is_rejected() = runBlocking {
        val store = StagedStore(java.io.File(java.lang.System.getProperty("java.io.tmpdir"), "gesture_ctrl_test6"))
        runBlocking {
            store.insert(makeStagedContent())
        }
        val conn = object : ConnectionService {
            override suspend fun getLocalDeviceInfo(): DeviceInfo = DeviceInfo("d1", "D", Platform.Windows, "FLW/1.0")
            override suspend fun getTrustedDevices(): List<Device> = emptyList()
            override suspend fun discoverPeers(): List<DiscoveredPeer> = emptyList()
            override suspend fun connectToDevice(deviceId: String): Boolean = true
            override suspend fun disconnectDevice(deviceId: String) {}
            override suspend fun isDeviceConnected(deviceId: String): Boolean = false
            override suspend fun initiateSend(deviceId: String, contentId: String): TransferInitiationResult =
                TransferInitiationResult("t1", TransferState.CONNECTING)
            override suspend fun cancelTransfer(transferId: String): Boolean = true
            override suspend fun getTransferStatus(transferId: String): TransferRecord? = null
            override suspend fun getActiveTransfers(): List<TransferRecord> = emptyList()
            override suspend fun shutdown() {}
        }
        val controller = makeController(store, conn)
        controller.arm()
        controller.selectTarget("device_1")

        var result: GestureResult? = null
        val capturingController = GestureController(
            connectionService = conn,
            stagedStore = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onResult = { result = it }
        )
        capturingController.arm()
        capturingController.selectTarget("device_1")

        val event = GestureEvent.LowConfidence(
            timestamp = System.currentTimeMillis(),
            gestureType = "SEND",
            confidence = 0.3f
        )
        capturingController.handleEvent(event)

        assertTrue(result is GestureResult.Rejected)
        val rejected = result as GestureResult.Rejected
        assertTrue(rejected.reason.contains("below threshold"))
    }

    @Test
    fn controller_canSend_returns_correct_state() = runBlocking {
        val store = StagedStore(java.io.File(java.lang.System.getProperty("java.io.tmpdir"), "gesture_ctrl_test7"))
        val conn = object : ConnectionService {
            override suspend fun getLocalDeviceInfo(): DeviceInfo = DeviceInfo("d1", "D", Platform.Windows, "FLW/1.0")
            override suspend fun getTrustedDevices(): List<Device> = emptyList()
            override suspend fun discoverPeers(): List<DiscoveredPeer> = emptyList()
            override suspend fun connectToDevice(deviceId: String): Boolean = true
            override suspend fun disconnectDevice(deviceId: String) {}
            override suspend fun isDeviceConnected(deviceId: String): Boolean = false
            override suspend fun initiateSend(deviceId: String, contentId: String): TransferInitiationResult =
                TransferInitiationResult("t1", TransferState.CONNECTING)
            override suspend fun cancelTransfer(transferId: String): Boolean = true
            override suspend fun getTransferStatus(transferId: String): TransferRecord? = null
            override suspend fun getActiveTransfers(): List<TransferRecord> = emptyList()
            override suspend fun shutdown() {}
        }
        val controller = makeController(store, conn)

        // Not armed
        assertFalse(controller.canSend())

        controller.arm()
        // Armed but no target
        assertFalse(controller.canSend())

        controller.selectTarget("device_1")
        // Armed + target but no staged content
        assertFalse(controller.canSend())

        runBlocking {
            store.insert(makeStagedContent())
        }
        // All conditions met
        assertTrue(controller.canSend())
    }
}
