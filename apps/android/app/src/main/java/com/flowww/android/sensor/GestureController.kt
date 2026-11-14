package com.flowww.android.sensor

import com.flowww.android.connection.ConnectionService
import com.flowww.android.content.ContentLifecycleState
import com.flowww.android.content.StagedContent
import com.flowww.android.storage.StagedStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Result of a gesture event handled by the controller.
 */
sealed class GestureResult {
    data class Accepted(val gestureType: String, val action: String) : GestureResult()
    data class Rejected(val gestureType: String, val reason: String) : GestureResult()
    data class Ignored(val reason: String) : GestureResult()
}

/**
 * GestureController is the safety layer between the GestureEngine and the
 * ConnectionService. It enforces preconditions before routing gesture
 * events to handoff operations.
 *
 * The controller must not execute transfers directly; it delegates to
 * ConnectionService.
 */
class GestureController(
    private val connectionService: ConnectionService,
    private val stagedStore: StagedStore,
    private val scope: CoroutineScope,
    private val onResult: (GestureResult) -> Unit = {}
) {

    private var selectedTargetDeviceId: String? = null
    private var activeTransferId: String? = null
    private var isArmed: Boolean = false

    /**
     * Set the currently selected trusted target device.
     */
    fun selectTarget(deviceId: String) {
        selectedTargetDeviceId = deviceId
    }

    /**
     * Clear the selected target.
     */
    fun clearTarget() {
        selectedTargetDeviceId = null
    }

    /**
     * Arm the controller (enable gesture-to-handoff routing).
     */
    fun arm() {
        isArmed = true
    }

    /**
     * Disarm the controller (disable gesture-to-handoff routing).
     */
    fun disarm() {
        isArmed = false
    }

    /**
     * Set the active transfer ID (for CANCEL precondition).
     */
    fun setActiveTransfer(transferId: String?) {
        activeTransferId = transferId
    }

    /**
     * Handle a gesture event from the engine.
     *
     * Preconditions are checked before any action is taken.
     */
    fun handleEvent(event: GestureEvent) {
        if (!isArmed) {
            onResult.invoke(GestureResult.Ignored("Controller not armed"))
            return
        }

        scope.launch {
            when (event) {
                is GestureEvent.Send -> handleSend(event)
                is GestureEvent.Receive -> handleReceive(event)
                is GestureEvent.Cancel -> handleCancel(event)
                is GestureEvent.LowConfidence -> {
                    onResult.invoke(GestureResult.Rejected(
                        event.gestureType,
                        "Confidence ${"%.2f".format(event.confidence)} below threshold"
                    ))
                }
                is GestureEvent.CalibrationRequired -> {
                    onResult.invoke(GestureResult.Ignored("Calibration required"))
                }
                is GestureEvent.Idle -> {
                    // No action
                }
            }
        }
    }

    private suspend fun handleSend(event: GestureEvent.Send) {
        // Precondition: staged content must exist
        val staged = stagedStore.all()
            .firstOrNull { it.lifecycleState == ContentLifecycleState.STAGED }
            ?: run {
                onResult.invoke(GestureResult.Rejected("SEND", "No staged content available"))
                return
            }

        // Precondition: trusted target must be selected
        val targetId = selectedTargetDeviceId
            ?: run {
                onResult.invoke(GestureResult.Rejected("SEND", "No trusted target selected"))
                return
            }

        // Precondition: confidence must be sufficient
        if (event.confidence < 0.6f) {
            onResult.invoke(GestureResult.Rejected("SEND", "Confidence too low"))
            return
        }

        // Execute via ConnectionService
        try {
            val result = connectionService.initiateSend(targetId, staged.contentId)
            activeTransferId = result.transferId
            onResult.invoke(GestureResult.Accepted(
                "SEND",
                "Transfer initiated: ${result.transferId}"
            ))
        } catch (e: Exception) {
            onResult.invoke(GestureResult.Rejected("SEND", e.message ?: "Transfer failed"))
        }
    }

    private suspend fun handleReceive(event: GestureEvent.Receive) {
        // Precondition: trusted target must be selected
        val targetId = selectedTargetDeviceId
            ?: run {
                onResult.invoke(GestureResult.Rejected("RECEIVE", "No trusted target selected"))
                return
            }

        // Precondition: confidence must be sufficient
        if (event.confidence < 0.6f) {
            onResult.invoke(GestureResult.Rejected("RECEIVE", "Confidence too low"))
            return
        }

        // In v4.3, RECEIVE is a mock operation; real integration comes in v4.4
        onResult.invoke(GestureResult.Accepted(
            "RECEIVE",
            "Receive requested from $targetId (mock - v4.3)"
        ))
    }

    private suspend fun handleCancel(event: GestureEvent.Cancel) {
        // Precondition: active cancellable operation must exist
        val transferId = activeTransferId
            ?: run {
                onResult.invoke(GestureResult.Rejected("CANCEL", "No active transfer to cancel"))
                return
            }

        // Precondition: confidence must be sufficient
        if (event.confidence < 0.6f) {
            onResult.invoke(GestureResult.Rejected("CANCEL", "Confidence too low"))
            return
        }

        try {
            val cancelled = connectionService.cancelTransfer(transferId)
            if (cancelled) {
                activeTransferId = null
                onResult.invoke(GestureResult.Accepted("CANCEL", "Transfer cancelled"))
            } else {
                onResult.invoke(GestureResult.Rejected("CANCEL", "Transfer not cancellable"))
            }
        } catch (e: Exception) {
            onResult.invoke(GestureResult.Rejected("CANCEL", e.message ?: "Cancel failed"))
        }
    }

    /**
     * Check whether SEND is currently possible (for UI enablement).
     */
    fun canSend(): Boolean {
        return isArmed &&
            selectedTargetDeviceId != null &&
            stagedStore.all().any { it.lifecycleState == ContentLifecycleState.STAGED }
    }

    /**
     * Check whether RECEIVE is currently possible (for UI enablement).
     */
    fun canReceive(): Boolean {
        return isArmed && selectedTargetDeviceId != null
    }

    /**
     * Check whether CANCEL is currently possible (for UI enablement).
     */
    fun canCancel(): Boolean {
        return isArmed && activeTransferId != null
    }
}
