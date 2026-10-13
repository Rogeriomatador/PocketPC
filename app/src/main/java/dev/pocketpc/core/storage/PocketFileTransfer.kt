package dev.pocketpc.core.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_TRANSFER_ENTRY_COUNT = 5_000
private const val MAX_TRANSFER_DEPTH = 32
private const val MAX_TRANSFER_FILE_BYTES = 512L * 1024L * 1024L
private const val MAX_TRANSFER_TOTAL_BYTES = 1024L * 1024L * 1024L

data class PocketFileTransferReport(
    val operation: PocketClipboardOperation,
    val destinationName: String,
    val fileCount: Int,
    val directoryCount: Int,
    val copiedBytes: Long,
    val sourceDeleted: Boolean,
)

/**
 * Pastes a PocketPC clipboard item into a PocketDrive directory.
 *
 * COPY and CUT share the same transactional copy path. CUT never deletes the
 * source until the destination has been fully materialized and finalized. If
 * source deletion is refused by the provider, the copied destination is kept
 * and the report explicitly says the cut was not completed.
 */
suspend fun pastePocketFileClipboard(
    context: Context,
    destinationDirectoryUri: String,
    item: PocketFileClipboardItem,
): Result<PocketFileTransferReport> =
    withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        var temporaryTarget: DocumentFile? = null

        try {
            val destination =
                resolveTransferDirectory(
                    context = appContext,
                    uriString = destinationDirectoryUri,
                )
            check(destination.canWrite()) {
                "A pasta de destino não permite escrita."
            }

            val source =
                DocumentFile.fromSingleUri(
                    appContext,
                    Uri.parse(item.entry.uri),
                ) ?: error("O item da área de transferência não está mais acessível.")
            check(source.exists()) {
                "O item da área de transferência não existe mais."
            }

            if (source.isDirectory) {
                ensureTransferDestinationIsOutsideSource(
                    source = source,
                    destinationUri = destination.uri.toString(),
                )
            }

            val requestedName =
                sanitizePocketImportedFileName(
                    source.name ?: item.entry.name
                ).ifBlank { if (source.isDirectory) "Pasta" else "Arquivo" }
            val finalName = uniqueTransferChildName(destination, requestedName)
            val temporaryName =
                ".pocketpc-part-${System.nanoTime()}-$finalName"
            val counters = TransferCounters()

            val finalized =
                if (source.isDirectory) {
                    claimTransferEntry(counters)
                    counters.directoryCount++
                    val tempRoot =
                        checkNotNull(destination.createDirectory(temporaryName)) {
                            "O PocketDrive recusou criar a pasta temporária."
                        }
                    temporaryTarget = tempRoot

                    copyTransferDirectoryChildren(
                        context = appContext,
                        source = source,
                        destination = tempRoot,
                        depth = 1,
                        counters = counters,
                    )

                    check(tempRoot.renameTo(finalName)) {
                        "A cópia terminou, mas o provedor recusou finalizar o nome da pasta."
                    }
                    tempRoot
                } else {
                    val tempFile =
                        checkNotNull(
                            destination.createFile(
                                source.type
                                    ?.takeIf { it.isNotBlank() }
                                    ?: item.entry.mimeType
                                        ?.takeIf { it.isNotBlank() }
                                    ?: "application/octet-stream",
                                temporaryName,
                            )
                        ) {
                            "O PocketDrive recusou criar o arquivo temporário."
                        }
                    temporaryTarget = tempFile
                    copyTransferFile(
                        context = appContext,
                        source = source,
                        destination = tempFile,
                        counters = counters,
                    )
                    check(tempFile.renameTo(finalName)) {
                        "A cópia terminou, mas o provedor recusou finalizar o nome do arquivo."
                    }
                    tempFile
                }

            temporaryTarget = null

            val sourceDeleted =
                if (item.operation == PocketClipboardOperation.CUT) {
                    runCatching { source.delete() }.getOrDefault(false)
                } else {
                    false
                }

            Result.success(
                PocketFileTransferReport(
                    operation = item.operation,
                    destinationName = finalized.name ?: finalName,
                    fileCount = counters.fileCount,
                    directoryCount = counters.directoryCount,
                    copiedBytes = counters.totalBytes,
                    sourceDeleted = sourceDeleted,
                )
            )
        } catch (error: Throwable) {
            runCatching { temporaryTarget?.delete() }
            Result.failure(error)
        }
    }

private data class TransferCounters(
    var entryCount: Int = 0,
    var fileCount: Int = 0,
    var directoryCount: Int = 0,
    var totalBytes: Long = 0L,
)

private fun copyTransferDirectoryChildren(
    context: Context,
    source: DocumentFile,
    destination: DocumentFile,
    depth: Int,
    counters: TransferCounters,
) {
    check(depth <= MAX_TRANSFER_DEPTH) {
        "A pasta ultrapassa $MAX_TRANSFER_DEPTH níveis e não será copiada."
    }

    source.listFiles().forEach { child ->
        claimTransferEntry(counters)
        val childName =
            sanitizePocketImportedFileName(child.name ?: "item")
                .ifBlank { "item" }
                .take(180)

        if (child.isDirectory) {
            val childDestination =
                checkNotNull(destination.createDirectory(childName)) {
                    "Não foi possível criar a pasta $childName no destino."
                }
            counters.directoryCount++
            copyTransferDirectoryChildren(
                context = context,
                source = child,
                destination = childDestination,
                depth = depth + 1,
                counters = counters,
            )
        } else {
            val targetName = uniqueTransferChildName(destination, childName)
            var childDestination: DocumentFile? = null
            try {
                val outputDocument =
                    checkNotNull(
                        destination.createFile(
                            child.type?.takeIf { it.isNotBlank() }
                                ?: "application/octet-stream",
                            targetName,
                        )
                    ) {
                        "Não foi possível criar $targetName no destino."
                    }
                childDestination = outputDocument
                copyTransferFile(
                    context = context,
                    source = child,
                    destination = outputDocument,
                    counters = counters,
                )
            } catch (error: Throwable) {
                runCatching { childDestination?.delete() }
                throw error
            }
        }
    }
}

private fun copyTransferFile(
    context: Context,
    source: DocumentFile,
    destination: DocumentFile,
    counters: TransferCounters,
) {
    val declaredSize = source.length()
    check(declaredSize <= 0L || declaredSize <= MAX_TRANSFER_FILE_BYTES) {
        "O arquivo ${source.name ?: "selecionado"} ultrapassa 512 MiB."
    }
    if (declaredSize > 0L) {
        check(counters.totalBytes <= MAX_TRANSFER_TOTAL_BYTES - declaredSize) {
            "A operação ultrapassa o limite total de 1 GiB do PocketPC."
        }
    }

    var fileBytes = 0L
    context.contentResolver
        .openInputStream(source.uri)
        ?.buffered(64 * 1024)
        ?.use { input ->
            context.contentResolver
                .openOutputStream(destination.uri, "w")
                ?.buffered(64 * 1024)
                ?.use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue

                        fileBytes += count.toLong()
                        counters.totalBytes += count.toLong()
                        check(fileBytes <= MAX_TRANSFER_FILE_BYTES) {
                            "O arquivo ${source.name ?: "selecionado"} ultrapassa 512 MiB."
                        }
                        check(counters.totalBytes <= MAX_TRANSFER_TOTAL_BYTES) {
                            "A operação ultrapassa o limite total de 1 GiB do PocketPC."
                        }
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                }
                ?: error("Não foi possível abrir o destino para escrita.")
        }
        ?: error("Não foi possível abrir ${source.name ?: "o arquivo"} para leitura.")

    counters.fileCount++
}

private fun claimTransferEntry(counters: TransferCounters) {
    counters.entryCount++
    check(counters.entryCount <= MAX_TRANSFER_ENTRY_COUNT) {
        "A operação ultrapassa $MAX_TRANSFER_ENTRY_COUNT itens."
    }
}

/**
 * Prevents copying a directory into itself or one of its descendants. This is
 * checked before any destination object is created, avoiding recursive
 * self-copy loops through SAF providers.
 */
private fun ensureTransferDestinationIsOutsideSource(
    source: DocumentFile,
    destinationUri: String,
) {
    check(source.uri.toString() != destinationUri) {
        "Não é possível colar uma pasta dentro dela mesma."
    }

    var visited = 0
    fun scan(directory: DocumentFile, depth: Int): Boolean {
        check(depth <= MAX_TRANSFER_DEPTH) {
            "A origem ultrapassa $MAX_TRANSFER_DEPTH níveis."
        }
        for (child in directory.listFiles()) {
            visited++
            check(visited <= MAX_TRANSFER_ENTRY_COUNT) {
                "A origem ultrapassa $MAX_TRANSFER_ENTRY_COUNT itens."
            }
            if (child.uri.toString() == destinationUri) return true
            if (child.isDirectory && scan(child, depth + 1)) return true
        }
        return false
    }

    check(!scan(source, 1)) {
        "Não é possível colar uma pasta dentro de uma de suas próprias subpastas."
    }
}

private fun resolveTransferDirectory(
    context: Context,
    uriString: String,
): DocumentFile {
    val uri = Uri.parse(uriString)
    val directory =
        runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
            ?: DocumentFile.fromSingleUri(context, uri)
            ?: error("Não foi possível acessar a pasta de destino.")
    check(directory.exists() && directory.isDirectory) {
        "O destino não está acessível como pasta."
    }
    return directory
}

private fun uniqueTransferChildName(
    parent: DocumentFile,
    requested: String,
): String {
    if (parent.findFile(requested) == null) return requested

    val dot = requested.lastIndexOf('.')
    val hasExtension = dot > 0 && dot < requested.lastIndex
    val base = if (hasExtension) requested.substring(0, dot) else requested
    val extension = if (hasExtension) requested.substring(dot) else ""

    for (index in 2 until 10_000) {
        val candidate = "${base.take(150)} ($index)$extension"
        if (parent.findFile(candidate) == null) return candidate
    }
    error("Não foi possível gerar um nome único para $requested.")
}
