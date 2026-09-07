package dev.pocketpc.core.storage

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StorageRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("pocketpc-storage", Context.MODE_PRIVATE)

    var rootUriString: String?
        get() = prefs.getString(KEY_ROOT_URI, null)
        private set(value) {
            prefs.edit().putString(KEY_ROOT_URI, value).apply()
        }

    fun persistRoot(uri: Uri): Result<Unit> = runCatching {
        val oldUri = rootUriString?.let(Uri::parse)
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val readOnly = Intent.FLAG_GRANT_READ_URI_PERMISSION

        runCatching { context.contentResolver.takePersistableUriPermission(uri, readWrite) }
            .recoverCatching { context.contentResolver.takePersistableUriPermission(uri, readOnly) }
            .getOrThrow()

        rootUriString = uri.toString()
        if (oldUri != null && oldUri != uri) releasePersistedPermission(oldUri)
    }

    fun clearRoot() {
        rootUriString?.let(Uri::parse)?.let(::releasePersistedPermission)
        rootUriString = null
    }

    private fun releasePersistedPermission(uri: Uri) {
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, readWrite) }
            .recoverCatching {
                context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
    }

    suspend fun list(uriString: String): Result<StorageListing> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(uriString)
            val directory = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
                ?: DocumentFile.fromSingleUri(context, uri)
                ?: error("Não foi possível abrir esta pasta.")

            if (!directory.exists()) error("A pasta autorizada não existe mais.")
            if (!directory.isDirectory) error("O URI selecionado não representa uma pasta.")

            val entries = directory.listFiles()
                .map { file ->
                    StorageEntry(
                        name = file.name ?: "(sem nome)",
                        uri = file.uri.toString(),
                        directory = file.isDirectory,
                        size = file.length(),
                        mimeType = file.type,
                        lastModified = file.lastModified(),
                    )
                }
                .sortedWith(
                    compareByDescending<StorageEntry> { it.directory }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                )

            StorageListing(
                directoryName = directory.name ?: "Pasta",
                entries = entries,
            )
        }
    }

    suspend fun createDirectory(
        parentUriString: String,
        name: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanName = validateName(name)
            val parent = resolveDirectory(parentUriString)
            checkNotNull(parent.createDirectory(cleanName)) {
                "O provedor de arquivos recusou criar a pasta."
            }
        }
    }

    suspend fun rename(
        entry: StorageEntry,
        newName: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanName = validateName(newName)
            val document =
                DocumentFile.fromSingleUri(
                    context,
                    Uri.parse(entry.uri),
                ) ?: error("Não foi possível acessar o item.")
            check(document.renameTo(cleanName)) {
                "O provedor de arquivos recusou renomear o item."
            }
        }
    }

    suspend fun delete(entry: StorageEntry): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val document =
                    DocumentFile.fromSingleUri(
                        context,
                        Uri.parse(entry.uri),
                    ) ?: error("Não foi possível acessar o item.")
                check(document.delete()) {
                    "O provedor de arquivos recusou excluir o item."
                }
            }
        }

    fun openFile(entry: StorageEntry): Result<Unit> = runCatching {
        require(!entry.directory) { "Diretórios devem ser navegados dentro do PocketPC." }
        val uri = Uri.parse(entry.uri)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, entry.mimeType ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            throw IllegalStateException("Nenhum app instalado consegue abrir este tipo de arquivo.", error)
        }
    }

    private fun resolveDirectory(uriString: String): DocumentFile {
        val uri = Uri.parse(uriString)
        val directory =
            runCatching {
                DocumentFile.fromTreeUri(context, uri)
            }.getOrNull()
                ?: DocumentFile.fromSingleUri(context, uri)
                ?: error("Não foi possível abrir esta pasta.")

        check(directory.exists()) {
            "A pasta autorizada não existe mais."
        }
        check(directory.isDirectory) {
            "O URI selecionado não representa uma pasta."
        }
        return directory
    }

    private fun validateName(raw: String): String {
        val value = raw.trim()
        require(value.isNotEmpty()) {
            "O nome não pode ficar vazio."
        }
        require(
            '/' !in value &&
                '\\' !in value &&
                value != "." &&
                value != ".."
        ) {
            "Nome inválido para arquivo ou pasta."
        }
        return value
    }

    companion object {
        private const val KEY_ROOT_URI = "root-uri"
    }
}
