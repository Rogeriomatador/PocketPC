package dev.pocketpc.core.storage

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream

class StorageRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("pocketpc-storage", Context.MODE_PRIVATE)

    val volumeId: String?
        get() = prefs.getString(KEY_DRIVE_VOLUME_ID, null)

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
        if (oldUri != uri) {
            prefs.edit()
                .remove(KEY_DRIVE_SCHEMA_VERSION)
                .remove(KEY_DRIVE_VOLUME_ID)
                .apply()
        }
        if (oldUri != null && oldUri != uri) releasePersistedPermission(oldUri)
    }

    fun clearRoot() {
        rootUriString?.let(Uri::parse)?.let(::releasePersistedPermission)
        rootUriString = null
        prefs.edit()
            .remove(KEY_DRIVE_SCHEMA_VERSION)
            .remove(KEY_DRIVE_VOLUME_ID)
            .apply()
    }

    suspend fun ensurePocketDrive(
        rootUri: String = requireNotNull(rootUriString) {
            "Nenhuma unidade PocketDrive foi configurada."
        },
    ): Result<PocketDriveMount> =
        withContext(Dispatchers.IO) {
            DRIVE_MUTEX.withLock {
                runCatching {
                    val root = resolveDirectory(rootUri)
                    check(root.canWrite()) {
                        "A pasta escolhida não permite escrita. " +
                            "Escolha uma pasta gravável para o PocketDrive."
                    }

                    // Existing metadata is validated before changing the
                    // directory layout. A future/corrupt volume therefore
                    // fails closed instead of being silently rewritten.
                    val existingMetadata =
                        readPocketDriveMetadata(root)

                    val directories =
                        PocketDriveDirectory.entries
                            .associateWith { directory ->
                                val existing =
                                    root.findFile(
                                        directory.folderName
                                    )
                                val folder =
                                    when {
                                        existing == null ->
                                            checkNotNull(
                                                root.createDirectory(
                                                    directory.folderName
                                                )
                                            ) {
                                                "Não foi possível criar " +
                                                    directory.folderName
                                            }

                                        existing.isDirectory ->
                                            existing

                                        else ->
                                            error(
                                                "Existe um arquivo chamado " +
                                                    directory.folderName +
                                                    " onde o PocketDrive " +
                                                    "precisa de uma pasta."
                                            )
                                    }

                                folder.uri.toString()
                            }

                    val metadata =
                        existingMetadata
                            ?: createPocketDriveMetadata(root)

                    prefs.edit()
                        .putInt(
                            KEY_DRIVE_SCHEMA_VERSION,
                            metadata.schemaVersion,
                        )
                        .putString(
                            KEY_DRIVE_VOLUME_ID,
                            metadata.volumeId,
                        )
                        .apply()

                    PocketDriveMount(
                        rootUri = root.uri.toString(),
                        directories = directories,
                        metadata = metadata,
                    )
                }
            }
        }

    private fun readPocketDriveMetadata(
        root: DocumentFile,
    ): PocketDriveMetadata? {
        val document =
            root.findFile(POCKET_DRIVE_METADATA_FILE)
                ?: return null

        check(!document.isDirectory) {
            "$POCKET_DRIVE_METADATA_FILE não pode ser uma pasta."
        }

        val raw =
            context.contentResolver
                .openInputStream(document.uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?: error(
                    "Não foi possível ler $POCKET_DRIVE_METADATA_FILE."
                )

        val decoded =
            PocketDriveMetadata.decode(raw)
                ?: error(
                    "$POCKET_DRIVE_METADATA_FILE está corrompido " +
                        "ou incompleto."
                )

        return try {
            validatePocketDriveMetadata(decoded)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException(
                "$POCKET_DRIVE_METADATA_FILE inválido: " +
                    (error.message ?: "formato desconhecido"),
                error,
            )
        }
    }

    private fun createPocketDriveMetadata(
        root: DocumentFile,
    ): PocketDriveMetadata {
        readPocketDriveMetadata(root)?.let {
            return it
        }

        val metadata = newPocketDriveMetadata()
        var target: DocumentFile? = null

        try {
            target =
                checkNotNull(
                    root.createFile(
                        "application/octet-stream",
                        POCKET_DRIVE_METADATA_FILE,
                    )
                ) {
                    "Não foi possível criar " +
                        POCKET_DRIVE_METADATA_FILE +
                        "."
                }

            check(
                target.name == POCKET_DRIVE_METADATA_FILE
            ) {
                "O provedor alterou o nome de " +
                    POCKET_DRIVE_METADATA_FILE +
                    "; o PocketDrive não será ativado."
            }

            context.contentResolver
                .openOutputStream(target.uri, "w")
                ?.bufferedWriter(Charsets.UTF_8)
                ?.use { writer ->
                    writer.write(metadata.encode())
                    writer.flush()
                }
                ?: error(
                    "Não foi possível gravar " +
                        POCKET_DRIVE_METADATA_FILE +
                        "."
                )

            return metadata
        } catch (error: Throwable) {
            runCatching { target?.delete() }
            throw error
        }
    }

    suspend fun pocketDirectoryUri(
        directory: PocketDriveDirectory,
    ): Result<String> =
        ensurePocketDrive().mapCatching { mount ->
            requireNotNull(mount.uriFor(directory)) {
                "Diretório lógico indisponível: " +
                    directory.displayName
            }
        }

    fun systemVolume(): PocketSystemVolume {
        val root =
            File(
                context.filesDir,
                "pocketpc-system",
            ).apply { mkdirs() }
        val runtime =
            File(root, "runtime").apply { mkdirs() }
        val packages =
            File(root, "packages").apply { mkdirs() }
        val cache =
            File(
                context.cacheDir,
                "pocketpc-system",
            ).apply { mkdirs() }
        val temporary =
            File(cache, "temp").apply { mkdirs() }

        return PocketSystemVolume(
            rootPath = root.absolutePath,
            cachePath = cache.absolutePath,
            runtimePath = runtime.absolutePath,
            packagesPath = packages.absolutePath,
            temporaryPath = temporary.absolutePath,
        )
    }

    private fun pocketSafGateway(): PocketSafGateway =
        PocketSafGateway(
            context,
            requireNotNull(rootUriString) {
                "Conecte o PocketDrive antes de usar o Explorador."
            },
        )

    private fun releasePersistedPermission(uri: Uri) {
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, readWrite) }
            .recoverCatching {
                context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
    }

    suspend fun list(uriString: String): Result<StorageListing> =
        withContext(Dispatchers.IO) {
            runCatching {
                val saf = pocketSafGateway()
                val directory = saf.info(uriString)
                check(directory.directory) {
                    "O URI selecionado não representa uma pasta."
                }
                StorageListing(
                    directoryName = directory.name,
                    entries = saf.children(uriString),
                )
            }
        }

    suspend fun createDirectory(
        parentUriString: String,
        name: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val saf = pocketSafGateway()
            val cleanName = validateStorageName(name)
            check(
                saf.children(parentUriString).none {
                    it.name.equals(cleanName, ignoreCase = true)
                }
            ) {
                "Já existe um item com este nome na pasta."
            }
            saf.create(
                parentUriString,
                android.provider.DocumentsContract.Document.MIME_TYPE_DIR,
                cleanName,
            )
            Unit
        }
    }

    suspend fun rename(
        entry: StorageEntry,
        newName: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val saf = pocketSafGateway()
            val renamed = saf.rename(
                saf.canonical(entry.uri),
                validateStorageName(newName),
            )
            val updated = saf.info(renamed.toString())
            PocketPcPackageRegistry(context).update(
                oldUri = entry.uri,
                entry = updated,
            )
            Unit
        }
    }

    suspend fun delete(entry: StorageEntry): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val saf = pocketSafGateway()
                check(saf.delete(saf.canonical(entry.uri))) {
                    "O provedor de arquivos recusou excluir o item."
                }
                PocketPcPackageRegistry(context).remove(entry.uri)
                Unit
            }
        }

    /**
     * File-only transactional transfer. The source survives failures during
     * streaming or destination finalization; CUT removes it only after the
     * new item is complete. No file is overwritten.
     */
    suspend fun transferFile(
        entry: StorageEntry,
        sourceParentUri: String,
        destinationUri: String,
        move: Boolean,
    ): Result<StorageEntry> = withContext(Dispatchers.IO) {
        DRIVE_MUTEX.withLock {
            runCatching {
                val saf = pocketSafGateway()
                val source = saf.info(entry.uri)
                check(!source.directory) {
                    "Cópia de pastas ainda não está disponível."
                }
                check(
                    saf.children(sourceParentUri).any {
                        it.uri == source.uri
                    }
                ) {
                    "O arquivo de origem mudou de lugar."
                }
                check(source.name == entry.name) {
                    "O arquivo foi renomeado desde a seleção."
                }
                val parent = saf.canonical(destinationUri)
                check(saf.info(parent.toString()).directory) {
                    "O destino não é uma pasta."
                }
                if (move) {
                    check(
                        saf.canonical(sourceParentUri) != parent
                    ) {
                        "O arquivo já está nesta pasta."
                    }
                }
                val targetName = allocatePocketTransferName(
                    source.name,
                    saf.children(parent.toString()).map { it.name },
                )
                val temporaryName =
                    ".pocketpc-transfer-" +
                        java.util.UUID.randomUUID().toString()
                var unfinished: Uri? = null
                try {
                    val temporary = saf.create(
                        parent.toString(),
                        source.mimeType
                            ?.takeIf { it.isNotBlank() }
                            ?: "application/octet-stream",
                        temporaryName,
                    )
                    unfinished = temporary
                    context.contentResolver
                        .openInputStream(saf.canonical(source.uri))
                        ?.use { input ->
                            context.contentResolver
                                .openOutputStream(temporary, "w")
                                ?.use { output ->
                                    input.copyTo(output, 256 * 1024)
                                    output.flush()
                                }
                                ?: error("O destino não aceita gravação.")
                        }
                        ?: error("A origem não pode ser lida.")

                    val finalized = saf.rename(temporary, targetName)
                    unfinished = finalized
                    val result = saf.info(finalized.toString())
                    check(result.name == targetName) {
                        "O provedor alterou o nome do arquivo."
                    }
                    unfinished = null

                    if (move) {
                        check(saf.delete(saf.canonical(source.uri))) {
                            "A cópia foi concluída, mas o original não pôde " +
                                "ser removido. Ambos os arquivos foram preservados."
                        }
                        PocketPcPackageRegistry(context).remove(source.uri)
                    }
                    PocketPcPackageRegistry(context).record(result)
                    result
                } catch (failure: Throwable) {
                    unfinished?.let { candidate ->
                        runCatching { saf.delete(candidate) }
                    }
                    throw failure
                }
            }
        }
    }

    suspend fun importIntoPocketDrive(
        directory: PocketDriveDirectory,
        fileName: String,
        mimeType: String?,
        source: InputStream,
    ): Result<StorageEntry> = withContext(Dispatchers.IO) {
        DRIVE_MUTEX.withLock {
            runCatching {
                val mount = ensurePocketDrive().getOrThrow()
                val directoryUri = requireNotNull(mount.uriFor(directory)) {
                    "Diretório lógico indisponível: " +
                        directory.displayName
                }
                val saf = pocketSafGateway()
                val cleanName = sanitizePocketImportedFileName(fileName)
                val finalName = allocatePocketTransferName(
                    cleanName,
                    saf.children(directoryUri).map { it.name },
                )
                val temporaryName =
                    ".pocketpc-part-" +
                        java.util.UUID.randomUUID().toString()
                var unfinished: Uri? = null
                try {
                    val temporary = saf.create(
                        directoryUri,
                        mimeType?.takeIf { it.isNotBlank() }
                            ?: "application/octet-stream",
                        temporaryName,
                    )
                    unfinished = temporary
                    context.contentResolver
                        .openOutputStream(temporary, "w")
                        ?.use { output ->
                            source.copyTo(output, 256 * 1024)
                            output.flush()
                        }
                        ?: error(
                            "Não foi possível abrir o destino para escrita."
                        )

                    val finalized = saf.rename(temporary, finalName)
                    unfinished = finalized
                    val result = saf.info(finalized.toString())
                    check(result.name == finalName) {
                        "O provedor alterou o nome do download."
                    }
                    unfinished = null
                    result
                } catch (failure: Throwable) {
                    unfinished?.let { candidate ->
                        runCatching { saf.delete(candidate) }
                    }
                    throw failure
                }
            }
        }
    }

    fun openFile(entry: StorageEntry): Result<Unit> =
        runCatching {
            require(!entry.directory) {
                "Diretórios devem ser navegados dentro do PocketPC."
            }

            val lowerName =
                entry.name.lowercase()
            val fileClass =
                classifyPocketFile(entry.name)
            val uri = Uri.parse(entry.uri)

            when {
                lowerName.endsWith(".apk") -> {
                    requestAndroidPackageInstall(uri)
                }

                lowerName.endsWith(".apks") ||
                    lowerName.endsWith(".xapk") -> {
                    error(
                        "Pacote Android em bundle detectado. " +
                            "APKS/XAPK ainda precisa de um instalador " +
                            "de bundles compatível."
                    )
                }

                fileClass ==
                    PocketFileClass.PC_INSTALLER -> {
                    error(
                        "Pacote de PC detectado: ${entry.name}. " +
                            "Ele está armazenado no PocketDrive, mas " +
                            "a execução aguarda um runtime Windows " +
                            "compatível realmente validado."
                    )
                }

                else -> {
                    val intent =
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(
                                uri,
                                entry.mimeType ?: "*/*",
                            )
                            addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK
                            )
                        }
                    try {
                        context.startActivity(intent)
                    } catch (
                        error:
                        ActivityNotFoundException
                    ) {
                        throw IllegalStateException(
                            "Nenhum app instalado consegue " +
                                "abrir este tipo de arquivo.",
                            error,
                        )
                    }
                }
            }
        }

    private fun requestAndroidPackageInstall(
        uri: Uri,
    ) {
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O &&
            !context.packageManager
                .canRequestPackageInstalls()
        ) {
            val settingsIntent =
                Intent(
                    Settings
                        .ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse(
                        "package:${context.packageName}"
                    ),
                ).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }

            context.startActivity(settingsIntent)
            error(
                "Permita 'Instalar apps desconhecidos' " +
                    "para o PocketPC e abra o APK novamente."
            )
        }

        val installIntent =
            Intent(
                Intent.ACTION_INSTALL_PACKAGE,
                uri,
            ).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }

        try {
            context.startActivity(installIntent)
        } catch (
            error: ActivityNotFoundException
        ) {
            throw IllegalStateException(
                "O instalador de pacotes do Android " +
                    "não está disponível.",
                error,
            )
        }
    }

    private fun uniqueChildName(
        parent: DocumentFile,
        requested: String,
    ): String {
        if (parent.findFile(requested) == null) {
            return requested
        }

        val dot = requested.lastIndexOf('.')
        val hasExtension =
            dot > 0 && dot < requested.lastIndex
        val base =
            if (hasExtension) {
                requested.substring(0, dot)
            } else {
                requested
            }
        val extension =
            if (hasExtension) {
                requested.substring(dot)
            } else {
                ""
            }

        var index = 2
        while (index < 10_000) {
            val candidate =
                "$base ($index)$extension"
            if (parent.findFile(candidate) == null) {
                return candidate
            }
            index++
        }

        error(
            "Não foi possível gerar um nome único " +
                "para $requested."
        )
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

    companion object {
        private const val KEY_ROOT_URI = "root-uri"
        private const val KEY_DRIVE_SCHEMA_VERSION =
            "pocket-drive-schema-version"
        private const val KEY_DRIVE_VOLUME_ID =
            "pocket-drive-volume-id"

        private val DRIVE_MUTEX = Mutex()
    }
}
