package com.flowww.android.data.local

import com.flowww.android.domain.models.DeviceTrustLevel
import com.flowww.android.domain.models.LocalTrustedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The store for the device records kept on this phone.
 *
 * A record is organisational only: the store keeps the trust level that says
 * so, and it never silently loses data.
 */
class TrustedDeviceStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun device(id: String, name: String = "Desktop") = LocalTrustedDevice(
        deviceId = id,
        displayName = name,
        platform = "Windows",
        fingerprint = "fp-$id",
        addedAt = 1_700_000_000_000L,
        lastSeenAt = null,
        note = null
    )

    @Test
    fun devicesSurviveARestart() {
        val dir = temp.newFolder("devices")
        TrustedDeviceStore(dir).put(device("a"))

        val reopened = TrustedDeviceStore(dir)
        val loaded = reopened.devices.value

        assertEquals(1, loaded.size)
        assertEquals("a", loaded.first().deviceId)
        assertEquals("Desktop", loaded.first().displayName)
    }

    @Test
    fun recordsAreStoredAsLocalRecordsOnly() {
        val store = TrustedDeviceStore(temp.newFolder("devices"))
        store.put(device("a"))

        assertEquals(DeviceTrustLevel.LOCAL_RECORD_ONLY, store.find("a")?.trustLevel)
        assertTrue(
            DeviceTrustLevel.LOCAL_RECORD_ONLY.explanation.contains("not proof")
        )
    }

    @Test
    fun puttingTheSameIdReplacesTheRecord() {
        val store = TrustedDeviceStore(temp.newFolder("devices"))

        store.put(device("a", "Old name"))
        store.put(device("a", "New name"))

        assertEquals(1, store.devices.value.size)
        assertEquals("New name", store.find("a")?.displayName)
    }

    @Test
    fun removeDeletesTheRecord() {
        val store = TrustedDeviceStore(temp.newFolder("devices"))
        store.put(device("a"))
        store.put(device("b"))

        assertTrue(store.remove("a"))
        assertNull(store.find("a"))
        assertEquals(1, store.devices.value.size)
        assertTrue(!store.remove("a"))
    }

    @Test
    fun corruptMetadataIsQuarantinedInsteadOfCrashing() {
        val dir = temp.newFolder("devices")
        File(dir, "trusted_devices.store").writeText("not a serialized device list")

        val store = TrustedDeviceStore(dir)

        assertTrue(store.devices.value.isEmpty())
        assertTrue(File(dir, "corrupt/trusted_devices.store").exists())
    }

    @Test
    fun findReturnsNullForAnUnknownDevice() {
        val store = TrustedDeviceStore(temp.newFolder("devices"))
        assertNull(store.find("missing"))
        assertNotNull(store.devices.value)
    }
}
