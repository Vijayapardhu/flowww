package com.flowww.android.ffi

import com.flowww.android.FlowwwError
import com.flowww.android.connection.Device
import com.flowww.android.pairing.PairingResult
import com.flowww.android.pairing.PairingService
import com.flowww.android.pairing.PairingUriParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pairing facade backed by the Rust node; the parser is only UX validation. */
class FfiPairingService(private val bridge: NodeBridge) : PairingService {
    override suspend fun validatePairingUri(uri: String) = withContext(Dispatchers.IO) {
        PairingUriParser.parse(uri).also {
            if (PairingUriParser.isExpired(it)) {
                throw FlowwwError.PairingExpired("Pairing URI has expired")
            }
        }
    }

    override suspend fun initiatePairing(uri: String): PairingResult {
        return try {
            validatePairingUri(uri)
            bridge.start()
            val device = bridge.call { bridge.node().pairWithUri(uri.trim()) }
                .getOrElse { throw it }
            PairingResult.Success(device.deviceId, device.deviceName)
        } catch (_: FlowwwError.PairingExpired) {
            PairingResult.Expired
        } catch (error: FlowwwError) {
            PairingResult.Failure(error.message ?: "Pairing failed")
        } catch (error: Throwable) {
            PairingResult.Failure(error.message ?: "Pairing failed")
        }
    }

    override suspend fun cancelPairing() {
        bridge.start()
        bridge.node().cancelPairing()
    }

    override suspend fun getTrustedDevices(): List<Device> {
        bridge.start()
        return bridge.call { bridge.node().listTrusted() }
            .getOrElse { throw it }
            .map { FfiMappers.run { it.toDomain() } }
    }

    override suspend fun forgetDevice(deviceId: String) {
        bridge.start()
        bridge.call { bridge.node().revokePeer(deviceId) }.getOrElse { throw it }
    }
}
