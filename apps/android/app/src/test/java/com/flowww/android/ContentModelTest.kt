package com.flowww.android

import com.flowww.android.content.ContentLifecycleState
import com.flowww.android.content.ContentType
import com.flowww.android.content.StagePayload
import com.flowww.android.content.StagedContent
import com.flowww.android.content.newContentId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ContentModelTest {

    @Test
    fun newContentId_isUnique() {
        val id1 = newContentId()
        val id2 = newContentId()
        assertNotNull(id1)
        assertNotNull(id2)
        assertEquals(false, id1 == id2)
        assertTrue(id1.startsWith("content_"))
    }

    @Test
    fun stagedContent_canTransferFromStaged() {
        val item = StagedContent(
            contentId = "content_test",
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
        assertTrue(item.canTransfer())
    }

    @Test
    fun stagedContent_canTransferFromReserved() {
        val item = StagedContent(
            contentId = "content_test",
            contentType = ContentType.TEXT,
            lifecycleState = ContentLifecycleState.RESERVED,
            displayName = "test.txt",
            mimeType = "text/plain",
            size = 10,
            sourceApplication = "test",
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 60000,
            contentReference = "ref_1",
            authorizationContext = null
        )
        assertTrue(item.canTransfer())
    }

    @Test
    fun stagedContent_cannotTransferFromTransferred() {
        val item = StagedContent(
            contentId = "content_test",
            contentType = ContentType.TEXT,
            lifecycleState = ContentLifecycleState.TRANSFERRED,
            displayName = "test.txt",
            mimeType = "text/plain",
            size = 10,
            sourceApplication = "test",
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 60000,
            contentReference = "ref_1",
            authorizationContext = null
        )
        assertFalse(item.canTransfer())
    }

    @Test
    fun stagedContent_isExpired() {
        val now = System.currentTimeMillis()
        val expired = StagedContent(
            contentId = "content_test",
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
        assertTrue(expired.isExpired())
    }

    @Test
    fun stagedContent_notExpired() {
        val now = System.currentTimeMillis()
        val notExpired = StagedContent(
            contentId = "content_test",
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
        assertFalse(notExpired.isExpired())
    }
}
