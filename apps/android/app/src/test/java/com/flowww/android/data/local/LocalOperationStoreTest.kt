package com.flowww.android.data.local

import com.flowww.android.domain.models.LocalOperation
import com.flowww.android.domain.models.LocalOperationStatus
import com.flowww.android.domain.models.LocalOperationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The local operation store.
 *
 * Operations are the real record of what happened on the phone, so the store
 * has to survive a restart, keep the newest entries and quarantine corrupt
 * metadata instead of losing it.
 */
class LocalOperationStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun operation(
        id: String,
        status: LocalOperationStatus = LocalOperationStatus.SUCCESS,
        createdAt: Long = 1_700_000_000_000L
    ) = LocalOperation(
        id = id,
        type = LocalOperationType.LOCAL_EXPORT,
        status = status,
        title = "1 item · 1 KB",
        contentIds = listOf("c-$id"),
        fileNames = listOf("file-$id.bin"),
        totalBytes = 1024L,
        writtenBytes = 1024L,
        destination = "Downloads",
        createdAt = createdAt,
        finishedAt = createdAt + 10
    )

    @Test
    fun operationsSurviveARestart() {
        val dir = temp.newFolder("operations")
        LocalOperationStore(dir).put(operation("a"))

        val reopened = LocalOperationStore(dir)
        val loaded = reopened.operations.value

        assertEquals(1, loaded.size)
        assertEquals("a", loaded.first().id)
        assertEquals(LocalOperationType.LOCAL_EXPORT, loaded.first().type)
        assertEquals("Downloads", loaded.first().destination)
    }

    @Test
    fun puttingTheSameIdReplacesTheRecord() {
        val store = LocalOperationStore(temp.newFolder("operations"))

        store.put(operation("a", status = LocalOperationStatus.RUNNING))
        store.put(operation("a", status = LocalOperationStatus.SUCCESS))

        assertEquals(1, store.operations.value.size)
        assertEquals(LocalOperationStatus.SUCCESS, store.operations.value.first().status)
    }

    @Test
    fun operationsAreOrderedNewestFirst() {
        val store = LocalOperationStore(temp.newFolder("operations"))

        store.put(operation("old", createdAt = 100L))
        store.put(operation("new", createdAt = 200L))

        assertEquals("new", store.operations.value.first().id)
    }

    @Test
    fun findReturnsTheRecordOrNull() {
        val store = LocalOperationStore(temp.newFolder("operations"))
        store.put(operation("a"))

        assertNotNull(store.find("a"))
        assertNull(store.find("missing"))
    }

    @Test
    fun clearRemovesEveryRecord() {
        val store = LocalOperationStore(temp.newFolder("operations"))
        store.put(operation("a"))
        store.put(operation("b"))

        assertEquals(2, store.clear())
        assertTrue(store.operations.value.isEmpty())
    }

    @Test
    fun corruptMetadataIsQuarantinedInsteadOfCrashing() {
        val dir = temp.newFolder("operations")
        File(dir, "local_operations.store").writeText("not a serialized operation list")

        val store = LocalOperationStore(dir)

        assertTrue(store.operations.value.isEmpty())
        assertTrue(File(dir, "corrupt").isDirectory)
        assertTrue(File(dir, "corrupt/local_operations.store").exists())
    }

    @Test
    fun aFailedWriteDoesNotDestroyTheExistingRecords() {
        val dir = temp.newFolder("operations")
        val store = LocalOperationStore(dir)
        store.put(operation("a"))

        // A directory where the store file belongs makes the next write fail.
        File(dir, "local_operations.store").delete()
        File(dir, "local_operations.store").mkdirs()

        store.put(operation("b"))

        assertEquals(2, store.operations.value.size)
    }
}
