package dev.pocketpc.core.runtime

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

internal class SafPcProgramDirectorySource(context: Context, private val tree: Uri) : PcProgramDirectorySource {
    private val resolver = context.applicationContext.contentResolver
    init { require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree)) { "PC_PROGRAM_TREE_URI_INVALID" } }
    override val rootId: String = DocumentsContract.getTreeDocumentId(tree)

    fun selectedId(uri: Uri): String {
        require(uri.scheme == "content" && uri.authority == tree.authority) { "PC_PROGRAM_TARGET_NOT_IN_FOLDER" }
        return runCatching { DocumentsContract.getDocumentId(uri) }.getOrElse {
            error("PC_PROGRAM_TARGET_NOT_DOCUMENT_URI")
        }
    }

    override fun children(directoryId: String): List<PcProgramDocument> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directoryId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE)
        return resolver.query(uri, columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    require(size < 20_000) { "PC_PROGRAM_ENTRY_LIMIT" }
                    add(PcProgramDocument(cursor.getString(0) ?: error("PC_PROGRAM_ID_MISSING"),
                        cursor.getString(1) ?: error("PC_PROGRAM_NAME_MISSING"),
                        cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        if (cursor.isNull(3)) -1 else cursor.getLong(3)))
                }
            }
        } ?: error("PC_PROGRAM_LIST_FAILED")
    }

    override fun open(documentId: String) = resolver.openInputStream(
        DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
    ) ?: error("PC_PROGRAM_OPEN_FAILED")
}
