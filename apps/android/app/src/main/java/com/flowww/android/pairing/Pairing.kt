package com.flowww.android.pairing

import com.flowww.android.connection.DeviceInfo
import com.flowww.android.connection.Platform
import com.flowww.android.FlowwwError
import kotlinx.coroutines.Dispatchers
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

    /** The URI parsed and is not expired, but no pairing session could be started locally. */
    data class Unavailable(val reason: String) : PairingResult()

    data object Expired : PairingResult()
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
        // Java's URL decoder requires padded input; pairing URIs are emitted unpadded.
        val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
        val decoded = try {
            java.util.Base64.getUrlDecoder().decode(padded)
        } catch (_: IllegalArgumentException) {
            throw FlowwwError.InvalidUri(uri)
        }
        val json = String(decoded, java.nio.charset.StandardCharsets.UTF_8)

        return try {
            parsePairingJson(json)
        } catch (_: Exception) {
            throw FlowwwError.InvalidUri(uri)
        }
    }

    private fun parsePairingJson(json: String): PairingUriData {
        // Minimal JSON parser for the flat pairing payload (strings and scalars).
        // In production, use kotlinx.serialization or Gson.
        val trimmed = json.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw IllegalArgumentException("Pairing payload is not a JSON object")
        }
        val map = mutableMapOf<String, String>()
        val content = trimmed.substring(1, trimmed.length - 1) // Remove outer braces

        var current = ""
        var inString = false
        var key = ""
        var isKey = true

        fun commitValue() {
            if (!isKey && key.isNotBlank()) {
                map[key] = current.trim()
            }
            current = ""
            isKey = true
        }

        for (ch in content) {
            when {
                ch == '"' && !inString -> inString = true
                ch == '"' && inString -> {
                    inString = false
                    if (isKey) {
                        key = current
                        isKey = false
                    } else {
                        commitValue()
                    }
                    current = ""
                }
                ch == ':' && !inString -> { /* separator */ }
                ch == ',' && !inString -> commitValue()
                else -> current += ch
            }
        }
        if (current.isNotBlank()) commitValue()

        return PairingUriData(
            version = map["version"]?.toInt() ?: 1,
            sessionId = map["session_id"] ?: "",
            token = map["token"] ?: "",
            hostDevice = DeviceInfo(
                deviceId = map["host_device_device_id"] ?: "",
                deviceName = map["host_device_device_name"] ?: "Unknown",
                platform = parsePlatform(map["host_device_platform"]),
                protocolVersion = map["host_device_protocol_version"] ?: "FLW/1.0"
            ),
            hostPublicKey = map["host_public_key"] ?: "",
            hostTlsFingerprint = map["host_tls_fingerprint"] ?: "",
            endpoint = map["endpoint"] ?: "",
            nonce = map["nonce"] ?: "",
            expiresAt = map["expires_at"]?.toLong() ?: 0
        )
    }

    private fun parsePlatform(raw: String?): Platform = when (raw?.trim()?.lowercase()) {
        "windows" -> Platform.Windows
        "android" -> Platform.Android
        "web" -> Platform.Web
        "macos", "mac", "osx" -> Platform.Macos
        "linux" -> Platform.Linux
        else -> Platform.Unknown
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
class PairingServiceImpl : PairingService {

    override suspend fun validatePairingUri(uri: String): PairingUriData {
        return withContext(Dispatchers.IO) {
            PairingUriParser.parse(uri).apply {
                if (PairingUriParser.isExpired(this)) {
                    throw FlowwwError.PairingExpired("Pairing URI has expired")
                }
            }
        }
    }

    override suspend fun initiatePairing(uri: String): PairingResult {
        return withContext(Dispatchers.IO) {
            try {
                val data = validatePairingUri(uri)
                // No fabricated success: the actual protocol exchange (challenge,
                // signature verification, mTLS bootstrap) lives in the Rust core
                // and is not connected yet.
                PairingResult.Unavailable(
                    "URI is valid, but secure pairing cannot complete until the Rust protocol engine is connected."
                )
            } catch (e: FlowwwError.PairingExpired) {
                PairingResult.Expired
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
}
