package dev.pocketpc.core.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

private const val MAX_ZIP_ENTRY_COUNT = 5_000
private const val MAX_ZIP_PATH_DEPTH = 32
private const val MAX_ZIP_PATH_LENGTH = 1_024
private const val MAX_ZIP_SEGMENT_LENGTH = 180
private const val MAX_ZIP_FILE_BYTES = 512L * 1024L * 1024L
private const val MAX_ZIP_TOTAL_EXTRACTED_BYTES = 1024L * 1024L * 1024L

data class PocketZipEntryInfo(
    val path: String,
    val directory: Boolean,
    val compressedSize: Long,
    val uncompressedSize: Long,
)

data class PocketZipListing(
    val entries: List<PocketZipEntryInfo>,
    val fileCount: Int,
    val directoryCount: Int,
    val knownUncompressedBytes: Long,
)

data class PocketZipExtractionReport(
    val destinationName: String,
    val fileCount: Int,
    val directoryCount: Int,
    val extractedBytes: Long,
)

/**
 * Reads ZIP metadata directly from a PocketPC content URI.
 *
 * No generic Android file viewer is involved. Paths are validated while the
 * central directory/entry stream is traversed so malformed traversal paths are
 * rejected before the archive is presented as extractable.
 */
fun inspectPocketZip(
    context: Context,
    uriString: String,
): Result<PocketZipListing> =
    runCatching {
        val entries = mutableListOf<PocketZipEntryInfo>()
        var fileCount = 0
        var directoryCount = 0
        var knownBytes = 0L

        openZipInput(context, uriString).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val segments = validatePocketZipEntryPath(entry.name)
                val directory = entry.isDirectory || entry.name.endsWith('/')
                val path = segments.joinToString("/") + if (directory) "/" else ""

                if (entries.size >= MAX_ZIP_ENTRY_COUNT) {
                    error(
                        "O ZIP ultrapassa o limite de $MAX_ZIP_ENTRY_COUNT entradas do PocketPC."
                    )
                }

                if (directory) {
                    directoryCount++
                } else {
                    fileCount++
                    if (entry.size > 0L) {
                        knownBytes = Math.addExact(knownBytes, entry.size)
                    }
                }

                entries +=
                    PocketZipEntryInfo(
                        path = path,
                        directory = directory,
                        compressedSize = entry.compressedSize,
                        uncompressedSize = entry.size,
                    )
                zip.closeEntry()
            }
        }

        PocketZipListing(
            entries = entries,
            fileCount = fileCount,
            directoryCount = directoryCount,
            knownUncompressedBytes = knownBytes,
        )
    }

/**
 * Extracts a ZIP into a new folder under P:\\Downloads.
 *
 * Extraction is fail-closed: traversal/absolute paths, excessive nesting,
 * oversized entries and excessive total output abort the operation. A failed
 * extraction removes the destination folder instead of leaving a partial tree.
 */
suspend fun extractPocketZipToDownloads(
    context: Context,
    uriString: String,
    archiveFileName: String,
): Result<PocketZipExtractionReport> =
    withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val storage = StorageRepository(appContext)
        var extractionRoot: DocumentFile? = null

        try {
            val mount = storage.ensurePocketDrive().getOrThrow()
            val downloadsUri =
                requireNotNull(
                    mount.uriFor(PocketDriveDirectory.DOWNLOADS)
                ) {
                    "P:\\Downloads não está disponível."
                }
            val downloads = resolveZipDirectory(appContext, downloadsUri)
            check(downloads.canWrite()) {
                "P:\\Downloads não permite escrita."
            }

            val requestedFolder =
                archiveFileName
                    .substringBeforeLast('.', archiveFileName)
                    .let(::sanitizePocketImportedFileName)
                    .ifBlank { "Arquivo extraído" }
            val destinationName = uniqueZipChildName(
                parent = downloads,
                requested = requestedFolder,
            )
            extractionRoot =
                checkNotNull(downloads.createDirectory(destinationName)) {
                    "Não foi possível criar P:\\Downloads\\$destinationName."
                }

            var entryCount = 0
            var fileCount = 0
            var directoryCount = 0
            var totalExtracted = 0L

            openZipInput(appContext, uriString).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_ZIP_ENTRY_COUNT) {
                        error(
                            "O ZIP ultrapassa o limite de $MAX_ZIP_ENTRY_COUNT entradas do PocketPC."
                        )
                    }

                    val segments = validatePocketZipEntryPath(entry.name)
                    val directory = entry.isDirectory || entry.name.endsWith('/')

                    if (directory) {
                        ensureZipDirectories(
                            root = extractionRoot,
                            segments = segments,
                        )
                        directoryCount++
                    } else {
                        val parent =
                            ensureZipDirectories(
                                root = extractionRoot,
                                segments = segments.dropLast(1),
                            )
                        val requestedName = segments.last()
                        val targetName =
                            uniqueZipChildName(
                                parent = parent,
                                requested = requestedName,
                            )
                        var target: DocumentFile? = null

                        try {
                            target =
                                checkNotNull(
                                    parent.createFile(
                                        pocketZipMimeType(targetName),
                                        targetName,
                                    )
                                ) {
                                    "O PocketDrive recusou criar $targetName."
                                }

                            appContext.contentResolver
                                .openOutputStream(target.uri, "w")
                                ?.buffered()
                                ?.use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    var fileExtracted = 0L

                                    while (true) {
                                        val count = zip.read(buffer)
                                        if (count < 0) break
                                        if (count == 0) continue

                                        fileExtracted += count.toLong()
                                        totalExtracted += count.toLong()

                                        check(fileExtracted <= MAX_ZIP_FILE_BYTES) {
                                            "A entrada $requestedName ultrapassa o limite de 512 MiB."
                                        }
                                        check(totalExtracted <= MAX_ZIP_TOTAL_EXTRACTED_BYTES) {
                                            "A extração ultrapassa o limite total de 1 GiB do PocketPC."
                                        }

                                        output.write(buffer, 0, count)
                                    }
                                    output.flush()
                                }
                                ?: error(
                                    "Não foi possível gravar $targetName no PocketDrive."
                                )
                            fileCount++
                        } catch (error: Throwable) {
                            runCatching { target?.delete() }
                            throw error
                        }
                    }

                    zip.closeEntry()
                }
            }

            Result.success(
                PocketZipExtractionReport(
                    destinationName = destinationName,
                    fileCount = fileCount,
                    directoryCount = directoryCount,
                    extractedBytes = totalExtracted,
                )
            )
        } catch (error: Throwable) {
            runCatching { extractionRoot?.delete() }
            Result.failure(error)
        }
    }

/**
 * Pure validation used by the extractor and unit tests.
 * Rejects Zip Slip paths and Windows/absolute path forms rather than attempting
 * to normalize them into a potentially surprising destination.
 */
internal fun validatePocketZipEntryPath(raw: String): List<String> {
    require(raw.isNotBlank()) {
        "O ZIP contém uma entrada sem nome."
    }
    require(raw.length <= MAX_ZIP_PATH_LENGTH) {
        "O ZIP contém um caminho maior que $MAX_ZIP_PATH_LENGTH caracteres."
    }
    require('\u0000' !in raw) {
        "O ZIP contém um caminho com caractere NUL."
    }

    val normalized = raw.replace('\\', '/')
    require(!normalized.startsWith('/')) {
        "O ZIP contém um caminho absoluto."
    }
    require(!Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
        "O ZIP contém um caminho absoluto do Windows."
    }

    val segments = normalized.split('/').filter { it.isNotEmpty() }
    require(segments.isNotEmpty()) {
        "O ZIP contém uma entrada sem destino válido."
    }
    require(segments.size <= MAX_ZIP_PATH_DEPTH) {
        "O ZIP ultrapassa $MAX_ZIP_PATH_DEPTH níveis de pastas."
    }

    segments.forEach { segment ->
        require(segment != "." && segment != "..") {
            "O ZIP tentou sair da pasta de extração."
        }
        require(segment.length <= MAX_ZIP_SEGMENT_LENGTH) {
            "O ZIP contém um nome maior que $MAX_ZIP_SEGMENT_LENGTH caracteres."
        }
        require(segment.none { it.code < 32 }) {
            "O ZIP contém caracteres de controle no nome."
        }
        require(segment.none { it in WINDOWS_FORBIDDEN_ZIP_NAME_CHARS }) {
            "O ZIP contém caracteres incompatíveis com o PocketDrive."
        }
        validateStorageName(segment)
    }

    return segments
}

private fun openZipInput(
    context: Context,
    uriString: String,
): ZipInputStream {
    val stream =
        context.contentResolver
            .openInputStream(Uri.parse(uriString))
            ?: error("Não foi possível abrir o ZIP.")
    return ZipInputStream(BufferedInputStream(stream, 64 * 1024))
}

private fun resolveZipDirectory(
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

private fun ensureZipDirectories(
    root: DocumentFile?,
    segments: List<String>,
): DocumentFile {
    var current = requireNotNull(root) {
        "A pasta de extração não está disponível."
    }

    for (segment in segments) {
        val existing = current.findFile(segment)
        current =
            when {
                existing == null ->
                    checkNotNull(current.createDirectory(segment)) {
                        "Não foi possível criar a pasta $segment."
                    }

                existing.isDirectory -> existing
                else -> error(
                    "O ZIP possui conflito entre arquivo e pasta em $segment."
                )
            }
    }

    return current
}

private fun uniqueZipChildName(
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
        val maxBaseLength =
            (MAX_ZIP_SEGMENT_LENGTH - extension.length - suffix.length)
                .coerceAtLeast(1)
        val candidate = base.take(maxBaseLength) + suffix + extension
        if (parent.findFile(candidate) == null) return candidate
    }

    error("Não foi possível gerar um nome único para $requested.")
}

private fun pocketZipMimeType(name: String): String =
    when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "log", "ini", "cfg", "conf" -> "text/plain"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "html", "htm" -> "text/html"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

private val WINDOWS_FORBIDDEN_ZIP_NAME_CHARS =
    setOf('<', '>', ':', '"', '|', '?', '*')
