package com.flowww.android.data.local

import com.flowww.android.connection.TransferDirection
import com.flowww.android.connection.TransferRecord
import com.flowww.android.connection.TransferState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TransferHistoryStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = folder.newFolder("transfer_history")
    }

    private fun record(
        id: String,
        createdAt: Long = 1000L,
        status: TransferState = TransferState.COMPLETED
    ) = TransferRecord(
        id = id,
        sourceDeviceId = "src",
        destinationDeviceId = "dst",
        filename = "file-$id.bin",
        size = 100L,
        status = status,
        route = "direct",
        direction = TransferDirection.OUTGOING,
        bytesTransferred = 100L,
        error = null,
        createdAt = createdAt,
        completedAt = createdAt + 10,
        mimeType = "application/octet-stream"
    )

    @Test
    fun recordsSurviveReload() {
        val store = TransferHistoryStore(dir)
        store.record(record("t1", createdAt = 1000L))
        store.record(record("t2", createdAt = 2000L))

        val reopened = TransferHistoryStore(dir)
        assertEquals(listOf("t2", "t1"), reopened.records.value.map { it.id })
    }

    @Test
    fun sameIdUpdatesInsteadOfDuplicating() {
        val store = TransferHistoryStore(dir)
        store.record(record("t1", createdAt = 1000L, status = TransferState.TRANSFERRING))
        store.record(record("t1", createdAt = 1000L, status = TransferState.COMPLETED))

        assertEquals(1, store.records.value.size)
        assertEquals(TransferState.COMPLETED, store.records.value[0].status)
    }

    @Test
    fun historyIsCappedAt200Entries() {
        val store = TransferHistoryStore(dir)
        repeat(205) { i -> store.record(record("t$i", createdAt = 1000L + i)) }

        assertEquals(200, store.records.value.size)
        // Oldest 5 dropped; newest kept.
        assertFalse(store.records.value.any { it.id == "t0" })
        assertTrue(store.records.value.any { it.id == "t204" })
        assertEquals("t204", store.records.value[0].id)
    }

    @Test
    fun corruptHistoryFileIsQuarantined() {
        val store = TransferHistoryStore(dir)
        store.record(record("t1"))
        File(dir, "transfer_history.store").writeBytes("garbage-not-serialized".toByteArray())

        val reopened = TransferHistoryStore(dir)
        assertTrue(reopened.records.value.isEmpty())

        val quarantine = File(dir, "corrupt")
        assertTrue(
            "corrupt history should be moved to corrupt/",
            quarantine.listFiles()?.any { it.name == "transfer_history.store" } == true
        )
    }

    @Test
    fun recordAllMergesAndPersists() {
        val store = TransferHistoryStore(dir)
        store.recordAll(listOf(record("a", 1000L), record("b", 2000L)))

        val reopened = TransferHistoryStore(dir)
        assertEquals(2, reopened.records.value.size)
        assertEquals(setOf("a", "b"), reopened.records.value.map { it.id }.toSet())
    }
}
