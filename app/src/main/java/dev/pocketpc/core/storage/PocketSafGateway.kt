package dev.pocketpc.core.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * SAF operations anchored to the persisted PocketDrive tree grant.
 *
 * Use canonical child document URIs with the original tree grant
 * explicitly, rather than depending on DocumentFile wrapper behavior.
 */
internal class PocketSafGateway(
    private val context: Context,
    rootUriString: String,
) {
    private val resolver = context.contentResolver
    private val grantedTree = Uri.parse(rootUriString)
    private val grantedId = DocumentsContract.getTreeDocumentId(grantedTree)

    init {
        require(DocumentsContract.isTreeUri(grantedTree)) {
            "PocketDrive exige permissão de pasta SAF."
        }
    }

    fun canonical(rawUri: String): Uri {
        val candidate = Uri.parse(rawUri)
        require(
            DocumentsContract.isTreeUri(candidate) &&
                candidate.authority == grantedTree.authority &&
                DocumentsContract.getTreeDocumentId(candidate) == grantedId
        ) {
            "O item não pertence ao PocketDrive conectado."
        }
        val documentId =
            if (DocumentsContract.isDocumentUri(context, candidate)) {
                DocumentsContract.getDocumentId(candidate)
            } else {
                grantedId
            }
        return DocumentsContract.buildDocumentUriUsingTree(
            grantedTree,
            documentId,
        )
    }

    fun info(rawUri: String): StorageEntry =
        queryEntry(canonical(rawUri))

    fun children(rawDirectoryUri: String): List<StorageEntry> {
        val directory = canonical(rawDirectoryUri)
        require(info(directory.toString()).directory) {
            "O destino não é uma pasta."
        }
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            grantedTree,
            DocumentsContract.getDocumentId(directory),
        )
        val result = mutableListOf<StorageEntry>()
        resolver.query(childUri, PROJECTION, null, null, null)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    result += fromCursor(cursor)
                }
            } ?: error("Não foi possível listar os arquivos desta pasta.")
        return result.sortedWith(
            compareByDescending<StorageEntry> { it.directory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
    }

    fun create(
        parentUriString: String,
        mimeType: String,
        name: String,
    ): Uri {
        val parent = canonical(parentUriString)
        check(info(parent.toString()).directory) {
            "O destino não é uma pasta."
        }
        return checkNotNull(
            DocumentsContract.createDocument(
                resolver,
                parent,
                mimeType,
                validateStorageName(name),
            )
        ) {
            "O provedor de arquivos recusou a criação."
        }
    }

    fun rename(document: Uri, name: String): Uri =
        checkNotNull(
            DocumentsContract.renameDocument(
                resolver,
                document,
                validateStorageName(name),
            )
        ) {
            "O provedor de arquivos recusou finalizar o nome."
        }

    fun delete(document: Uri): Boolean =
        DocumentsContract.deleteDocument(resolver, document)

    private fun queryEntry(document: Uri): StorageEntry {
        resolver.query(document, PROJECTION, null, null, null)
            ?.use { cursor ->
                check(cursor.moveToFirst()) {
                    "O item não existe mais."
                }
                return fromCursor(cursor)
            }
        error("O provedor não permitiu consultar este item.")
    }

    private fun fromCursor(
        cursor: android.database.Cursor,
    ): StorageEntry {
        val id = cursor.getString(0)
            ?: error("O provedor retornou um ID vazio.")
        val name = cursor.getString(1) ?: "(sem nome)"
        val mime = cursor.getString(2)
        return StorageEntry(
            name = name,
            uri = DocumentsContract.buildDocumentUriUsingTree(
                grantedTree,
                id,
            ).toString(),
            directory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
            size = if (cursor.isNull(3)) 0L else cursor.getLong(3),
            mimeType = mime,
            lastModified = if (cursor.isNull(4)) 0L else cursor.getLong(4),
        )
    }

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
