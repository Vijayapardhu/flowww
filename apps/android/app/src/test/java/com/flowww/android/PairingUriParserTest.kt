package com.flowww.android

import com.flowww.android.pairing.PairingUriParser
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertThrows
import kotlin.test.assertTrue

class PairingUriParserTest {

    @Test
    fun parse_validUri_succeeds() {
        // Build a valid pairing URI
        val payload = """{
            "version":1,
            "session_id":"550e8400-e29b-41d4-a716-446655440000",
            "token":"dGVzdF90b2tlbl90b2tlbl90b2tlbl90b2tlbg",
            "host_device":{"device_id":"fw_host_0001","device_name":"Studio","platform":"windows","protocol_version":"FLW/1.0"},
            "host_public_key":"base64publickey32byteslongbase64publickey32byteslong",
            "host_tls_fingerprint":"deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
            "endpoint":"192.168.1.10:49152",
            "nonce":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "expires_at":9999999999999
        }"""

        val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        val uri = "flowww://pair/v1/$encoded"

        val data = PairingUriParser.parse(uri)
        assertNotNull(data)
        assertEquals(1, data.version)
        assertEquals("fw_host_0001", data.hostDevice.deviceId)
        assertEquals("Studio", data.hostDevice.deviceName)
        assertFalse(PairingUriParser.isExpired(data))
    }

    @Test
    fun parse_invalidPrefix_throws() {
        assertThrows<IllegalArgumentException> {
            PairingUriParser.parse("invalid://pair/v1/abc")
        }
    }

    @Test
    fun isExpired_expired_returnsTrue() {
        val data = com.flowww.android.pairing.PairingUriData(
            version = 1,
            sessionId = "test",
            token = "test",
            hostDevice = com.flowww.android.connection.DeviceInfo(
                deviceId = "fw_test",
                deviceName = "Test",
                platform = com.flowww.android.connection.Platform.Windows,
                protocolVersion = "FLW/1.0"
            ),
            hostPublicKey = "key",
            hostTlsFingerprint = "fp",
            endpoint = "127.0.0.1:49152",
            nonce = "nonce",
            expiresAt = System.currentTimeMillis() - 1000
        )
        assertTrue(PairingUriParser.isExpired(data))
    }

    @Test
    fun isExpired_notExpired_returnsFalse() {
        val data = com.flowww.android.pairing.PairingUriData(
            version = 1,
            sessionId = "test",
            token = "test",
            hostDevice = com.flowww.android.connection.DeviceInfo(
                deviceId = "fw_test",
                deviceName = "Test",
                platform = com.flowww.android.connection.Platform.Windows,
                protocolVersion = "FLW/1.0"
            ),
            hostPublicKey = "key",
            hostTlsFingerprint = "fp",
            endpoint = "127.0.0.1:49152",
            nonce = "nonce",
            expiresAt = System.currentTimeMillis() + 60000
        )
        assertFalse(PairingUriParser.isExpired(data))
    }
}
