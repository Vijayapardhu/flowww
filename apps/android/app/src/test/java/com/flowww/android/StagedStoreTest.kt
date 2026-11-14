package com.flowww.android

import com.flowww.android.content.ContentLifecycleState
import com.flowww.android.content.ContentType
import com.flowww.android.content.StagePayload
import com.flowww.android.content.StagedContent
import com.flowww.android.storage.StagedStore
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import java.io.File

class StagedStoreTest {

    @Test
    fun insertAndGet() = runTest {
        val tempDir = java.io.File(createTempDir(), "staged_test")
        val store = StagedStore(tempDir)

        val item = StagedContent(
            contentId = "content_test_1",
            contentType = ContentType.TEXT,
            lifecycleState = ContentLifecycleState.STAGED,
            displayName = "test.txt",
            mimeType = "text/plain",
            size = 10,
            sourceApplication = "test",
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 60000,
            contentReference = "ref_1",
            authorizationContext = null
        )

        store.insert(item)
        val retrieved = store.get("content_test_1")
        assertNotNull(retrieved)
        assertEquals("test.txt", retrieved?.displayName)
        assertEquals(ContentLifecycleState.STAGED, retrieved?.lifecycleState)
    }

    @Test
    fun get_nonexistent_returnsNull() = runTest {
        val tempDir = java.io.File(createTempDir(), "staged_test2")
        val store = StagedStore(tempDir)
        val result = store.get("nonexistent")
        assertNull(result)
    }

    @Test
    fun expire_marksExpired() = runTest {
        val tempDir = java.io.File(createTempDir(), "staged_test3")
        val store = StagedStore(tempDir)

        val now = System.currentTimeMillis()
        val item = StagedContent(
            contentId = "content_expire_test",
            contentType = ContentType.TEXT,
            lifecycleState = ContentLifecycleState.STAGED,
            displayName = "test.txt",
            mimeType = "text/plain",
            size = 10,
            sourceApplication = "test",
            createdAt = now,
            expiresAt = now - 1, // Already expired
            contentReference = "ref_1",
            authorizationContext = null
        )

        store.insert(item)
        val count = store.expire(now)
        assertEquals(1, count)

        val retrieved = store.get("content_expire_test")
        assertNull(retrieved) // Expired items should not be returned
    }

    @Test
    fun transitionState_works() = runTest {
        val tempDir = java.io.File(createTempDir(), "staged_test4")
        val store = StagedStore(tempDir)

        val now = System.currentTimeMillis()
        val item = StagedContent(
            contentId = "content_transition_test",
            contentType = ContentType.TEXT,
            lifecycleState = ContentLifecycleState.STAGED,
            displayName = "test.txt",
            mimeType = "text/plain",
            size = 10,
            sourceApplication = "test",
            createdAt = now,
            expiresAt = now + 60000,
            contentReference = "ref_1",
            authorizationContext = null
        )

        store.insert(item)
        val updated = store.transitionState("content_transition_test", ContentLifecycleState.RESERVED)
        assertEquals(ContentLifecycleState.RESERVED, updated.lifecycleState)

        val retrieved = store.get("content_transition_test")
        assertEquals(ContentLifecycleState.RESERVED, retrieved?.lifecycleState)
    }

    @Test
    fun transitionState_expired_throws() = runTest {
        val tempDir = java.io.File(createTempDir(), "staged_test5")
        val store = StagedStore(tempDir)

        val now = System.currentTimeMillis()
        val item = StagedContent(
            contentId = "content_expired_transition",
            contentType = ContentType.TEXT,
            lifecycleState = ContentLifecycleState.STAGED,
            displayName = "test.txt",
            mimeType = "text/plain",
            size = 10,
            sourceApplication = "test",
            createdAt = now,
            expiresAt = now - 1,
            contentReference = "ref_1",
            authorizationContext = null
        )

        store.insert(item)
        assertFailsWith<IllegalStateException> {
            store.transitionState("content_expired_transition", ContentLifecycleState.RESERVED)
        }
    }

    private fun createTempDir(): File {
        return File(System.getProperty("java.io.tmpdir"), "flowww_test_${System.currentTimeMillis()}")
    }
}
