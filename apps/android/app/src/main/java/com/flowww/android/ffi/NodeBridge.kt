package com.flowww.android.ffi

import android.content.Context
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import com.flowww.android.FlowwwError
import com.flowww.android.uniffi.FlowwwEvent
import com.flowww.android.uniffi.FlowwwEventListener
import com.flowww.android.uniffi.FlowwwException
import com.flowww.android.uniffi.FlowwwNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the one [FlowwwNode] this process is allowed to have.
 *
 * Responsibilities:
 * - start/stop the node against app-private directories (the Rust core is
 *   sandboxed to its data dir; the download dir lives next to it)
 * - register the listener BEFORE start so no early event is lost
 * - forward every [FlowwwEvent] onto a SharedFlow for the bridges and UIs
 * - hold the Wi-Fi multicast lock for as long as discovery is alive
 * - translate [FlowwwException] into the app's [FlowwwError] vocabulary
 *
 * Every call into the node blocks its thread (the FFI surface is
 * synchronous), so callers must already be on a background dispatcher.
 */
class NodeBridge(
    context: Context,
    secretStore: com.flowww.android.uniffi.PlatformSecretStore? = null,
) {
    private val appContext = context.applicationContext

    private val _events = MutableSharedFlow<FlowwwEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Every event the Rust core emits, in emission order. Drops oldest on overflow. */
    val events: SharedFlow<FlowwwEvent> = _events.asSharedFlow()

    private val node = FlowwwNode()
    private val lifecycleMutex = Mutex()

    private val listener = object : FlowwwEventListener {
        override fun onEvent(event: FlowwwEvent) {
            _events.tryEmit(event)
        }
    }
    private var listenerId: ULong? = null

    private val dataDir = File(appContext.filesDir, "flowww_node").also { it.mkdirs() }
    private val downloadDir = File(appContext.filesDir, "downloads").also { it.mkdirs() }
    private val secretStore = secretStore

    private var multicastLock: WifiManager.MulticastLock? = null

    /** Local identity once the node has started; null before/after. */
    @Volatile
    var localDevice: com.flowww.android.uniffi.DeviceDto? = null
        private set

    val isRunning: Boolean get() = runCatching { node.isRunning() }.getOrDefault(false)

    /**
     * Starts the node. Idempotent: a second call is a no-op. Blocking FFI call,
     * so this must not run on the main thread (the container calls it from
     * its own scope).
     */
    suspend fun start() {
        lifecycleMutex.withLock {
            if (isRunning) return
            withContext(Dispatchers.IO) {
                // Register first so PairingStarted/NodeStarted cannot race past us.
                val registeredId = node.addListener(listener)
                listenerId = registeredId
                try {
                    // Prefer the platform-protected store (Android Keystore)
                    // when one is supplied. Falls back to the Rust file store.
                    val device = secretStore?.let { store ->
                        node.startWithSecretStore(
                            dataDir.absolutePath,
                            deviceName(),
                            downloadDir.absolutePath,
                            store
                        )
                    } ?: node.start(
                        dataDir.absolutePath,
                        deviceName(),
                        downloadDir.absolutePath
                    )
                    localDevice = device
                    acquireMulticastLock()
                } catch (error: Throwable) {
                    runCatching { node.removeListener(registeredId) }
                    listenerId = null
                    throw error
                }
            }
        }
    }

    /** Stops the node and releases the multicast lock. Idempotent. */
    suspend fun stop() {
        lifecycleMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching { node.stop() }
                listenerId?.let { runCatching { node.removeListener(it) } }
                listenerId = null
                localDevice = null
                releaseMulticastLock()
            }
        }
    }

    /**
     * Explicit identity reset. Stops the node, destroys the Keystore-protected
     * identity record, the TLS pin and the trust database, and keeps downloads
     * and staged transfers. Every pairing must be re-established by scanning
     * a new QR code afterwards. Requires the node to be stopped first.
     */
    suspend fun resetIdentity() {
        lifecycleMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching { node.stop() }
                listenerId?.let { runCatching { node.removeListener(it) } }
                listenerId = null
                localDevice = null
                releaseMulticastLock()
                // The platform store clears its Keystore-backed record; the
                // Rust core removes its own identity/TLS/trust files.
                node.resetIdentity(dataDir.absolutePath, secretStore)
            }
        }
    }

    /** The node, for services that need APIs this bridge does not re-expose. */
    fun node(): FlowwwNode = node

    /**
     * Runs a node call, translating FFI failures into [FlowwwError] failures.
     * Callers stay honest about what went wrong without leaking Rust internals
     * (the FFI crate already sanitizes messages; nothing path-shaped crosses).
     */
    suspend fun <T> call(block: suspend () -> T): Result<T> = try {
        Result.success(withContext(Dispatchers.IO) { block() })
    } catch (error: Throwable) {
        Result.failure(
            when (error) {
                is FlowwwException -> mapFlowwwError(error)
                else -> FlowwwError.Internal(error.message ?: "Rust node call failed")
            }
        )
    }

    private fun deviceName(): String {
        val model = try { android.os.Build.MODEL?.trim() } catch (_: Throwable) { null }
        return if (model.isNullOrBlank()) "Flowww Android" else "Flowww $model"
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = ContextCompat.getSystemService(appContext, WifiManager::class.java) ?: return
            val lock = wifi.createMulticastLock("flowww-mdns").apply {
                setReferenceCounted(false)
            }
            lock.acquire()
            multicastLock = lock
        } catch (_: Exception) {
            // Discovery degrades to manual pairing; the lock is an optimisation,
            // never a reason to fail the node.
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.release()
        } catch (_: Exception) {
            // Double release or already released; nothing to do.
        }
        multicastLock = null
    }
}

/**
 * The generated error classes decorate their message as "message=<raw>" via an
 * override of [Exception.getMessage]. Stripping that prefix yields exactly the
 * sanitized text the Rust core produced; nothing else is added or removed.
 */
internal fun rawMessage(error: FlowwwException): String =
    error.message?.removePrefix("message=") ?: ""

/** Maps the FFI error surface onto the app's error vocabulary. */
internal fun mapFlowwwError(error: FlowwwException): FlowwwError = when (error) {
    is FlowwwException.Config -> FlowwwError.Internal(rawMessage(error))
    is FlowwwException.Storage -> FlowwwError.Internal(rawMessage(error))
    is FlowwwException.Crypto -> FlowwwError.Internal(rawMessage(error))
    is FlowwwException.Network -> FlowwwError.ConnectionFailed(rawMessage(error))
    is FlowwwException.Transfer -> FlowwwError.TransferError(rawMessage(error))
    is FlowwwException.Discovery -> FlowwwError.ConnectionFailed(rawMessage(error))
    is FlowwwException.Protocol -> FlowwwError.Internal(rawMessage(error))
    is FlowwwException.UnknownPeer -> FlowwwError.DeviceNotFound(rawMessage(error))
    is FlowwwException.PeerUnavailable -> FlowwwError.ConnectionFailed(rawMessage(error))
    is FlowwwException.OfferRejected -> FlowwwError.TransferError(rawMessage(error))
    is FlowwwException.PairingBusy -> FlowwwError.PairingFailed("Another pairing session is already active")
    is FlowwwException.NoPairing -> FlowwwError.PairingFailed("No pairing session is active")
    is FlowwwException.PairingFailed -> FlowwwError.PairingFailed(rawMessage(error))
    is FlowwwException.Timeout -> FlowwwError.ConnectionFailed("The operation timed out")
    is FlowwwException.Closed -> FlowwwError.ConnectionFailed("The connection was closed")
    is FlowwwException.UnexpectedMessage -> FlowwwError.Internal(rawMessage(error))
    is FlowwwException.PeerException -> FlowwwError.ConnectionFailed(rawMessage(error))
    is FlowwwException.NotStarted -> FlowwwError.Internal("The Flowww node is not running")
    is FlowwwException.AlreadyStarted -> FlowwwError.Internal("The Flowww node is already running")
    is FlowwwException.InvalidInput -> FlowwwError.InvalidContent(rawMessage(error))
    is FlowwwException.Internal -> FlowwwError.Internal(rawMessage(error))
}
