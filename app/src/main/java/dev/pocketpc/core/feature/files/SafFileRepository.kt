package dev.pocketpc.core.feature.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

data class SafEntry(
    val documentId: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val lastModified: Long?,
    val flags: Int,
    val uri: String,
) {
    val isDirectory: Boolean
        get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
}

class SafFileRepository(private val context: Context) {
    private val resolver = context.contentResolver
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun loadTreeUri(): Uri? =
        preferences.getString(KEY_TREE_URI, null)?.let(Uri::parse)

    fun persistTree(uri: Uri): Result<Unit> = runCatching {
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(uri, readWrite)
        } catch (_: SecurityException) {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        preferences.edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    fun forgetTree() {
        val uri = loadTreeUri()
        if (uri != null) {
            runCatching {
                resolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            runCatching {
                resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        preferences.edit().remove(KEY_TREE_URI).apply()
    }

    fun rootDocumentUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )

    fun listChildren(treeUri: Uri, documentUri: Uri? = null): List<SafEntry> {
        val parent = documentUri ?: rootDocumentUri(treeUri)
        val parentDocumentId = DocumentsContract.getDocumentId(parent)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            parentDocumentId,
        )

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )

        val entries = mutableListOf<SafEntry>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val flagsColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS)

            while (cursor.moveToNext()) {
                val documentId = cursor.getString(idColumn)
                val documentUriForEntry = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                entries += SafEntry(
                    documentId = documentId,
                    name = cursor.getString(nameColumn) ?: "(sem nome)",
                    mimeType = cursor.getString(mimeColumn) ?: "application/octet-stream",
                    sizeBytes = if (cursor.isNull(sizeColumn)) null else cursor.getLong(sizeColumn),
                    lastModified = if (cursor.isNull(modifiedColumn)) null else cursor.getLong(modifiedColumn),
                    flags = if (cursor.isNull(flagsColumn)) 0 else cursor.getInt(flagsColumn),
                    uri = documentUriForEntry.toString(),
                )
            }
        }

        return entries.sortedWith(
            compareByDescending<SafEntry> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
    }

    fun openExternal(entry: SafEntry): Result<Unit> = runCatching {
        val uri = Uri.parse(entry.uri)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, entry.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    companion object {
        private const val PREFERENCES = "pocketpc_files"
        private const val KEY_TREE_URI = "tree_uri"
    }
}
