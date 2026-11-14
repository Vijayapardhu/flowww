package com.flowww.android.pairing

import com.flowww.android.connection.DeviceInfo
import com.flowww.android.connection.Platform
import com.flowww.android.FlowwwError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Data model for the pairing payload received from a Windows desktop.
 *
 * The pairing URI format is: flowww://pair/v1/<base64url-encoded-json>
 */
data class PairingUriData(
    val version: Int,
    val sessionId: String,
    val token: String,
    val hostDevice: DeviceInfo,
    val hostPublicKey: String,
    val hostTlsFingerprint: String,
    val endpoint: String,
    val nonce: String,
    val expiresAt: Long
)

/**
 * Result of a pairing operation.
 */
sealed class PairingResult {
    data class Success(val deviceId: String, val deviceName: String) : PairingResult()
    data class Failure(val reason: String) : PairingResult()
    object Expired : PairingResult()
}

/**
 * Parses and validates a flowww pairing URI.
 */
object PairingUriParser {

    private const val PREFIX = "flowww://pair/v1/"

    fun parse(uri: String): PairingUriData {
        if (!uri.startsWith(PREFIX)) {
            throw FlowwwError.InvalidUri(uri)
        }

        val encoded = uri.substring(PREFIX.length)
        val decoded = java.util.Base64.getUrlDecoder().decode(encoded)
        val json = String(decoded, java.nio.charset.StandardCharsets.UTF_8)

        // Simple JSON parsing without external dependencies
        return parsePairingJson(json)
    }

    private fun parsePairingJson(json: String): PairingUriData {
        // Minimal JSON parser for the pairing payload
        // In production, use kotlinx.serialization or Gson
        val map = mutableMapOf<String, String>()
        val content = json.substring(1, json.length - 1) // Remove outer braces

        var current = ""
        var inString = false
        var key = ""
        var isKey = true

        for (ch in content) {
            when {
                ch == '"' && !inString -> inString = true
                ch == '"' && inString -> {
                    inString = false
                    if (isKey) {
                        key = current
                        isKey = false
                        current = ""
                    } else {
                        map[key] = current
                        isKey = true
                        current = ""
                    }
                }
                ch == ':' && !inString -> { /* skip */ }
                ch == ',' && !inString -> { /* skip */ }
                else -> if (inString) current += ch
            }
        }

        return PairingUriData(
            version = map["version"]?.toInt() ?: 1,
            sessionId = map["session_id"] ?: "",
            token = map["token"] ?: "",
            hostDevice = DeviceInfo(
                deviceId = map["host_device_device_id"] ?: "",
                deviceName = map["host_device_device_name"] ?: "Unknown",
                platform = Platform.valueOf(map["host_device_platform"]?.uppercase() ?: "UNKNOWN"),
                protocolVersion = map["host_device_protocol_version"] ?: "FLW/1.0"
            ),
            hostPublicKey = map["host_public_key"] ?: "",
            hostTlsFingerprint = map["host_tls_fingerprint"] ?: "",
            endpoint = map["endpoint"] ?: "",
            nonce = map["nonce"] ?: "",
            expiresAt = map["expires_at"]?.toLong() ?: 0
        )
    }

    fun isExpired(data: PairingUriData): Boolean {
        return System.currentTimeMillis() >= data.expiresAt
    }
}

/**
 * Interface for pairing operations that delegate to the Rust core via FFI.
 */
interface PairingService {
    suspend fun validatePairingUri(uri: String): PairingUriData
    suspend fun initiatePairing(uri: String): PairingResult
    suspend fun cancelPairing()
    suspend fun getTrustedDevices(): List<com.flowww.android.connection.Device>
    suspend fun forgetDevice(deviceId: String)
}

/**
 * Stub implementation of PairingService. In production, this delegates to
 * the Rust node via UniFFI/JNI FFI bindings.
 */
class PairingServiceImpl(private val scope: CoroutineScope) : PairingService {

    override suspend fun validatePairingUri(uri: String): PairingUriData {
        return withContext(Dispatchers.IO) {
            PairingUriParser.parse(uri).apply {
                if (PairingUriParser.isExpired(this)) {
                    throw FlowwwError.PairingFailed("Pairing URI has expired")
                }
            }
        }
    }

    override suspend fun initiatePairing(uri: String): PairingResult {
        return withContext(Dispatchers.IO) {
            try {
                val data = validatePairingUri(uri)
                // In production, this would call into Rust via FFI:
                // Ffi.pairWithUri(uri)
                scope.launch {
                    // Simulate async pairing - in production this is real
                    PairingServiceImpl.completePairing(data)
                }
                PairingResult.Success(data.hostDevice.deviceId, data.hostDevice.deviceName)
            } catch (e: FlowwwError) {
                PairingResult.Failure(e.message ?: "Pairing failed")
            } catch (e: Exception) {
                PairingResult.Failure(e.message ?: "Pairing failed")
            }
        }
    }

    override suspend fun cancelPairing() {
        // Cancel any ongoing pairing operation
    }

    override suspend fun getTrustedDevices(): List<com.flowww.android.connection.Device> {
        return withContext(Dispatchers.IO) {
            // In production, query the Rust node's trust store
            emptyList()
        }
    }

    override suspend fun forgetDevice(deviceId: String) {
        // Remove device from trust store
    }

    private companion object {
        fun completePairing(data: PairingUriData) {
            // Mark device as paired in local storage
        }
    }
}
