package com.flowww.android.transfer

import android.net.Uri

/**
 * Narrow SAF abstraction used by [ReceiveCommitter].
 *
 * The real implementation talks to DocumentsContract; tests supply an in-memory
 * fake so streaming, verification, duplicate naming and failure handling stay
 * covered without a device. Children are identified by their `content://`
 * URI string; nothing here exposes a filesystem path.
 */
interface DocumentWriter {
    /** Names of children already present in the tree. */
    fun existingNames(): Set<String>

    /** Creates a new child document and returns its URI string. */
    fun createChild(displayName: String, mimeType: String): String

    /** Opens the child for writing; may throw SecurityException. */
    fun openOutput(childUri: String): java.io.OutputStream

    /** Deletes a child document. */
    fun deleteChild(childUri: String): Boolean

    /** Display label of the tree (for UI and records). */
    fun treeLabel(): String
}

/**
 * Writes received files into a user-picked SAF tree.
 *
 * Nothing is opened or executed after writing; the caller verifies the digest.
 */
class SafDocumentWriter(
    private val treeUri: Uri,
    private val resolver: android.content.ContentResolver,
) : DocumentWriter {

    private val childrenUri: Uri by lazy {
        Uri.parse(
            android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            ).toString()
        )
    }

    override fun existingNames(): Set<String> {
        val result = mutableSetOf<String>()
        val projection = arrayOf(
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
            if (nameIndex < 0) return result
            while (cursor.moveToNext()) {
                result.add(cursor.getString(nameIndex))
            }
        }
        return result
    }

    override fun createChild(displayName: String, mimeType: String): String {
        val created = android.provider.DocumentsContract.createDocument(
            resolver, treeUri, mimeType, displayName
        ) ?: throw java.io.IOException("The chosen folder refused to create a file")
        return created.toString()
    }

    override fun openOutput(childUri: String): java.io.OutputStream {
        val uri = android.net.Uri.parse(childUri)
        return resolver.openOutputStream(uri)
            ?: throw java.io.IOException("The chosen folder is not writable")
    }

    override fun deleteChild(childUri: String): Boolean =
        android.provider.DocumentsContract.deleteDocument(resolver, android.net.Uri.parse(childUri))

    override fun treeLabel(): String {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        val name = docId.substringAfterLast(':').substringAfterLast('/')
        return name.ifBlank { "selected folder" }
    }
}
