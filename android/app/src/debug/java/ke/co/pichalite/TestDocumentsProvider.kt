package ke.co.pichalite

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import java.io.File
import java.util.UUID

/** A private, local-only document provider for real save/delete instrumentation. */
class TestDocumentsProvider : DocumentsProvider() {
    private val root get() = File(context!!.cacheDir, "test-documents").apply { mkdirs() }
    private fun file(id: String): File {
        val file = File(root, id).canonicalFile
        check(file.parentFile == root.canonicalFile)
        return file
    }
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf("root_id"))
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE)
        val cursor = MatrixCursor(columns)
        val file = if (documentId == "root") root else file(documentId)
        if (file.exists()) cursor.addRow(columns.map {
            when (it) {
                Document.COLUMN_DOCUMENT_ID -> documentId
                Document.COLUMN_DISPLAY_NAME -> file.name
                Document.COLUMN_MIME_TYPE -> if (documentId == "root") Document.MIME_TYPE_DIR else "image/jpeg"
                Document.COLUMN_FLAGS -> if (documentId == "root") Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_WRITE
                Document.COLUMN_SIZE -> file.length()
                else -> null
            }
        })
        return cursor
    }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = MatrixCursor(projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID))
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId == "root" && documentId != "root" && file(documentId).exists()
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        check(parentDocumentId == "root")
        val id = UUID.randomUUID().toString() + ".jpg"
        check(file(id).createNewFile())
        return id
    }
    override fun deleteDocument(documentId: String) { check(file(documentId).delete()) }
}
