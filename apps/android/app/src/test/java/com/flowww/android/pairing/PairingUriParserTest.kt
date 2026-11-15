package com.flowww.android.pairing

import com.flowww.android.FlowwwError
import com.flowww.android.connection.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PairingUriParserTest {

    private val service = PairingServiceImpl()

    private fun uriFor(json: String): String =
        "flowww://pair/v1/" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))

    private fun json(expiresAt: Long): String = """
        {"version":1,"session_id":"sess-1","token":"tok","host_device_device_id":"win-1","host_device_device_name":"Office PC","host_device_platform":"windows","host_device_protocol_version":"FLW/1.0","host_public_key":"key","host_tls_fingerprint":"fp","endpoint":"192.168.1.2:4400","nonce":"nonce-1","expires_at":"$expiresAt"}
    """.trimIndent()

    @Test
    fun validTtlUriParsesAllFields() = kotlinx.coroutines.runBlocking {
        val data = service.validatePairingUri(uriFor(json(System.currentTimeMillis() + 60_000L)))
        assertEquals(1, data.version)
        assertEquals("sess-1", data.sessionId)
        assertEquals("tok", data.token)
        assertEquals("win-1", data.hostDevice.deviceId)
        assertEquals("Office PC", data.hostDevice.deviceName)
        assertEquals(Platform.Windows, data.hostDevice.platform)
        assertEquals("192.168.1.2:4400", data.endpoint)
        assertEquals("nonce-1", data.nonce)
    }

    @Test
    fun expiredTtlThrowsPairingExpired() {
        val uri = uriFor(json(System.currentTimeMillis() - 60_000L))
        val error = assertThrows(FlowwwError.PairingExpired::class.java) {
            kotlinx.coroutines.runBlocking { service.validatePairingUri(uri) }
        }
        assertTrue(error.message!!.contains("expired", ignoreCase = true))
    }

    @Test
    fun badPrefixThrowsInvalidUri() {
        assertThrows(FlowwwError.InvalidUri::class.java) {
            PairingUriParser.parse("https://example.com/flowww")
        }
    }

    @Test
    fun malformedBase64ThrowsInvalidUri() {
        assertThrows(FlowwwError.InvalidUri::class.java) {
            PairingUriParser.parse("flowww://pair/v1/@@@not-base64@@@")
        }
    }

    @Test
    fun unparseableJsonThrowsInvalidUri() {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{not-json".toByteArray(Charsets.UTF_8))
        assertThrows(FlowwwError.InvalidUri::class.java) {
            PairingUriParser.parse("flowww://pair/v1/$encoded")
        }
    }

    @Test
    fun initiatePairingIsHonestUnavailableForValidUri() = kotlinx.coroutines.runBlocking {
        val result = service.initiatePairing(uriFor(json(System.currentTimeMillis() + 60_000L)))
        assertTrue(result is PairingResult.Unavailable)
        assertTrue((result as PairingResult.Unavailable).reason.contains("Rust"))
    }

    @Test
    fun initiatePairingExpiredYieldsExpiredResult() = kotlinx.coroutines.runBlocking {
        val result = service.initiatePairing(uriFor(json(System.currentTimeMillis() - 60_000L)))
        assertTrue(result is PairingResult.Expired)
    }
}
