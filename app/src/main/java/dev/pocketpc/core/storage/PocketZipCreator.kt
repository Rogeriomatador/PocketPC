package dev.pocketpc.core.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val MAX_CREATE_ZIP_ENTRY_COUNT = 5_000
private const val MAX_CREATE_ZIP_DEPTH = 32
private const val MAX_CREATE_ZIP_FILE_BYTES = 512L * 1024L * 1024L
private const val MAX_CREATE_ZIP_TOTAL_BYTES = 1024L * 1024L * 1024L

data class PocketZipCreationReport(
    val fileName: String,
    val fileCount: Int,
    val directoryCount: Int,
    val sourceBytes: Long,
)

suspend fun createPocketZipBesideEntry(
    context: Context,
    parentUriString: String,
    entry: StorageEntry,
): Result<PocketZipCreationReport> =
    withContext(Dispatchers.IO) {
        runCatching {
            val parent = resolveCreateZipDirectory(context, parentUriString)
            check(parent.canWrite()) {
                "A pasta atual não permite criar arquivos."
            }

            val source =
                parent.findFile(entry.name)
                    ?: error("O item selecionado não está mais disponível.")
            val outputName = uniqueCreateZipName(
                parent = parent,
                requested = pocketZipOutputName(entry.name),
            )
            val temporaryName =
                ".pocketpc-part-${System.nanoTime()}-$outputName"
            var target: DocumentFile? = null

            try {
                target =
                    checkNotNull(
                        parent.createFile(
                            "application/zip",
                            temporaryName,
                        )
                    ) {
                        "O PocketDrive recusou criar o ZIP temporário."
                    }

                val counters = ZipCreateCounters()
                context.contentResolver
                    .openOutputStream(target.uri, "w")
                    ?.buffered()
                    ?.use { raw ->
                        ZipOutputStream(raw).use { zip ->
                            if (source.isDirectory) {
                                val rootName = safeZipEntrySegment(
                                    source.name ?: entry.name
                                )
                                addDirectoryToZip(
                                    context = context,
                                    source = source,
                                    zip = zip,
                                    path = "$rootName/",
                                    depth = 1,
                                    counters = counters,
                                )
                            } else {
                                addFileToZip(
                                    context = context,
                                    source = source,
                                    zip = zip,
                                    path = safeZipEntrySegment(
                                        source.name ?: entry.name
                                    ),
                                    counters = counters,
                                )
                            }
                        }
                    }
                    ?: error("Não foi possível gravar o ZIP no PocketDrive.")

                check(target.renameTo(outputName)) {
                    "O ZIP foi criado, mas o provedor recusou finalizar o nome."
                }

                PocketZipCreationReport(
                    fileName = target.name ?: outputName,
                    fileCount = counters.fileCount,
                    directoryCount = counters.directoryCount,
                    sourceBytes = counters.totalBytes,
                )
            } catch (error: Throwable) {
                runCatching { target?.delete() }
                throw error
            }
        }
    }

internal fun pocketZipOutputName(sourceName: String): String {
    val safe = sanitizePocketImportedFileName(sourceName)
    val stem =
        if (safe.endsWith(".zip", ignoreCase = true)) {
            safe.dropLast(4).ifBlank { "Arquivo" }
        } else {
            safe
        }
    return "${stem.take(170)}.zip"
}

private data class ZipCreateCounters(
    var entryCount: Int = 0,
    var fileCount: Int = 0,
    var directoryCount: Int = 0,
    var totalBytes: Long = 0L,
)

private fun addDirectoryToZip(
    context: Context,
    source: DocumentFile,
    zip: ZipOutputStream,
    path: String,
    depth: Int,
    counters: ZipCreateCounters,
) {
    check(depth <= MAX_CREATE_ZIP_DEPTH) {
        "A pasta ultrapassa $MAX_CREATE_ZIP_DEPTH níveis e não será compactada."
    }
    claimCreateZipEntry(counters)
    zip.putNextEntry(ZipEntry(path))
    zip.closeEntry()
    counters.directoryCount++

    source.listFiles().forEach { child ->
        val childName = safeZipEntrySegment(
            child.name ?: "item"
        )
        if (child.isDirectory) {
            addDirectoryToZip(
                context = context,
                source = child,
                zip = zip,
                path = "$path$childName/",
                depth = depth + 1,
                counters = counters,
            )
        } else {
            addFileToZip(
                context = context,
                source = child,
                zip = zip,
                path = "$path$childName",
                counters = counters,
            )
        }
    }
}

private fun addFileToZip(
    context: Context,
    source: DocumentFile,
    zip: ZipOutputStream,
    path: String,
    counters: ZipCreateCounters,
) {
    claimCreateZipEntry(counters)
    val declaredSize = source.length()
    check(
        declaredSize <= 0L ||
            declaredSize <= MAX_CREATE_ZIP_FILE_BYTES
    ) {
        "O arquivo ${source.name ?: path} ultrapassa 512 MiB."
    }

    zip.putNextEntry(ZipEntry(path))
    var fileBytes = 0L
    try {
        context.contentResolver
            .openInputStream(source.uri)
            ?.buffered()
            ?.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue

                    fileBytes += count.toLong()
                    counters.totalBytes += count.toLong()
                    check(fileBytes <= MAX_CREATE_ZIP_FILE_BYTES) {
                        "O arquivo ${source.name ?: path} ultrapassa 512 MiB."
                    }
                    check(counters.totalBytes <= MAX_CREATE_ZIP_TOTAL_BYTES) {
                        "A seleção ultrapassa o limite de 1 GiB para compactação."
                    }
                    zip.write(buffer, 0, count)
                }
            }
            ?: error("Não foi possível ler ${source.name ?: path}.")
    } finally {
        zip.closeEntry()
    }
    counters.fileCount++
}

private fun claimCreateZipEntry(counters: ZipCreateCounters) {
    counters.entryCount++
    check(counters.entryCount <= MAX_CREATE_ZIP_ENTRY_COUNT) {
        "A seleção ultrapassa $MAX_CREATE_ZIP_ENTRY_COUNT entradas."
    }
}

private fun safeZipEntrySegment(raw: String): String =
    sanitizePocketImportedFileName(raw)
        .trim()
        .ifBlank { "item" }
        .take(180)

private fun resolveCreateZipDirectory(
    context: Context,
    uriString: String,
): DocumentFile {
    val uri = Uri.parse(uriString)
    val directory =
        runCatching {
            DocumentFile.fromTreeUri(context, uri)
        }.getOrNull()
            ?: DocumentFile.fromSingleUri(context, uri)
            ?: error("Não foi possível acessar a pasta atual.")
    check(directory.exists() && directory.isDirectory) {
        "A pasta atual não está acessível."
    }
    return directory
}

private fun uniqueCreateZipName(
    parent: DocumentFile,
    requested: String,
): String {
    if (parent.findFile(requested) == null) return requested

    val stem = requested.removeSuffix(".zip")
    for (index in 2 until 10_000) {
        val candidate = "${stem.take(160)} ($index).zip"
        if (parent.findFile(candidate) == null) return candidate
    }
    error("Não foi possível gerar um nome único para o ZIP.")
}
