package dev.pocketpc.core.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.GZIPInputStream

private const val MAX_GZIP_EXTRACTED_BYTES = 1024L * 1024L * 1024L

data class PocketGzipExtractionReport(
    val fileName: String,
    val extractedBytes: Long,
)

/**
 * Extracts a single-file GZIP stream into P:\\Downloads without delegating
 * anything to Android's generic file handlers.
 *
 * The output is written to a temporary DocumentFile first and only renamed to
 * the final name after the stream completes. Oversized streams fail closed and
 * the partial output is removed.
 */
suspend fun extractPocketGzipToDownloads(
    context: Context,
    uriString: String,
    archiveFileName: String,
): Result<PocketGzipExtractionReport> =
    withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val storage = StorageRepository(appContext)
        var target: DocumentFile? = null

        try {
            val mount = storage.ensurePocketDrive().getOrThrow()
            val downloadsUri =
                requireNotNull(
                    mount.uriFor(PocketDriveDirectory.DOWNLOADS)
                ) {
                    "P:\\Downloads não está disponível."
                }
            val downloads = resolveGzipDirectory(appContext, downloadsUri)
            check(downloads.canWrite()) {
                "P:\\Downloads não permite escrita."
            }

            val requestedName = pocketGzipOutputName(archiveFileName)
            val finalName = uniqueGzipChildName(downloads, requestedName)
            val temporaryName =
                ".pocketpc-part-${System.nanoTime()}-$finalName"

            target =
                checkNotNull(
                    downloads.createFile(
                        pocketGzipMimeType(finalName),
                        temporaryName,
                    )
                ) {
                    "O PocketDrive recusou criar o arquivo temporário."
                }

            var extractedBytes = 0L
            val rawInput =
                appContext.contentResolver
                    .openInputStream(Uri.parse(uriString))
                    ?: error("Não foi possível abrir o GZIP.")

            rawInput.buffered(64 * 1024).use { buffered ->
                GZIPInputStream(buffered, 64 * 1024).use { gzip ->
                    appContext.contentResolver
                        .openOutputStream(target.uri, "w")
                        ?.buffered(64 * 1024)
                        ?.use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = gzip.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue

                                extractedBytes += count.toLong()
                                check(extractedBytes <= MAX_GZIP_EXTRACTED_BYTES) {
                                    "O conteúdo expandido ultrapassa o limite de 1 GiB do PocketPC."
                                }
                                output.write(buffer, 0, count)
                            }
                            output.flush()
                        }
                        ?: error("Não foi possível gravar o conteúdo expandido.")
                }
            }

            check(target.renameTo(finalName)) {
                "O arquivo foi expandido, mas o provedor recusou finalizar o nome."
            }

            Result.success(
                PocketGzipExtractionReport(
                    fileName = target.name ?: finalName,
                    extractedBytes = extractedBytes,
                )
            )
        } catch (error: Throwable) {
            runCatching { target?.delete() }
            Result.failure(error)
        }
    }

internal fun pocketGzipOutputName(sourceName: String): String {
    val safe = sanitizePocketImportedFileName(sourceName)
    val lowered = safe.lowercase()
    val stem =
        when {
            lowered.endsWith(".tgz") ->
                safe.dropLast(4) + ".tar"
            lowered.endsWith(".gz") ->
                safe.dropLast(3)
            else -> safe + ".out"
        }

    return stem
        .ifBlank { "arquivo" }
        .take(180)
}

private fun resolveGzipDirectory(
    context: Context,
    uriString: String,
): DocumentFile {
    val uri = Uri.parse(uriString)
    val directory =
        runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
            ?: DocumentFile.fromSingleUri(context, uri)
            ?: error("Não foi possível acessar P:\\Downloads.")
    check(directory.exists() && directory.isDirectory) {
        "P:\\Downloads não está acessível como pasta."
    }
    return directory
}

private fun uniqueGzipChildName(
    parent: DocumentFile,
    requested: String,
): String {
    if (parent.findFile(requested) == null) return requested

    val dot = requested.lastIndexOf('.')
    val hasExtension = dot > 0 && dot < requested.lastIndex
    val base = if (hasExtension) requested.substring(0, dot) else requested
    val extension = if (hasExtension) requested.substring(dot) else ""

    for (index in 2 until 10_000) {
        val suffix = " ($index)"
        val candidate = base.take(160) + suffix + extension
        if (parent.findFile(candidate) == null) return candidate
    }
    error("Não foi possível gerar um nome único para $requested.")
}

private fun pocketGzipMimeType(name: String): String =
    when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "log", "ini", "cfg", "conf" -> "text/plain"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "html", "htm" -> "text/html"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "tar" -> "application/x-tar"
        else -> "application/octet-stream"
    }
