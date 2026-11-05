package dev.pocketpc.core.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream

private const val TAR_BLOCK_SIZE = 512
private const val MAX_TAR_ENTRY_COUNT = 5_000
private const val MAX_TAR_FILE_BYTES = 512L * 1024L * 1024L
private const val MAX_TAR_TOTAL_BYTES = 1024L * 1024L * 1024L
private const val MAX_TAR_PATH_DEPTH = 32
private const val MAX_TAR_PATH_LENGTH = 1_024
private const val MAX_TAR_SEGMENT_LENGTH = 180

data class PocketTarEntryInfo(
    val path: String,
    val directory: Boolean,
    val size: Long,
)

data class PocketTarListing(
    val entries: List<PocketTarEntryInfo>,
    val fileCount: Int,
    val directoryCount: Int,
    val totalBytes: Long,
)

data class PocketTarExtractionReport(
    val destinationName: String,
    val fileCount: Int,
    val directoryCount: Int,
    val extractedBytes: Long,
)

fun inspectPocketTar(
    context: Context,
    uriString: String,
): Result<PocketTarListing> =
    runCatching {
        val entries = mutableListOf<PocketTarEntryInfo>()
        var fileCount = 0
        var directoryCount = 0
        var totalBytes = 0L

        openTarInput(context, uriString).use { input ->
            while (true) {
                val header = readTarHeader(input) ?: break
                if (entries.size >= MAX_TAR_ENTRY_COUNT) {
                    error("O TAR ultrapassa o limite de $MAX_TAR_ENTRY_COUNT entradas.")
                }

                val segments = validatePocketTarEntryPath(header.path)
                val normalizedPath =
                    segments.joinToString("/") + if (header.directory) "/" else ""

                if (header.directory) {
                    directoryCount++
                } else {
                    check(header.size <= MAX_TAR_FILE_BYTES) {
                        "A entrada ${header.path} ultrapassa 512 MiB."
                    }
                    check(totalBytes <= MAX_TAR_TOTAL_BYTES - header.size) {
                        "O TAR ultrapassa o limite total de 1 GiB do PocketPC."
                    }
                    totalBytes += header.size
                    fileCount++
                }

                consumeExactTarBytes(input, header.size)
                consumeExactTarBytes(input, tarPadding(header.size))

                entries +=
                    PocketTarEntryInfo(
                        path = normalizedPath,
                        directory = header.directory,
                        size = header.size,
                    )
            }
        }

        PocketTarListing(
            entries = entries,
            fileCount = fileCount,
            directoryCount = directoryCount,
            totalBytes = totalBytes,
        )
    }

suspend fun extractPocketTarToDownloads(
    context: Context,
    uriString: String,
    archiveFileName: String,
): Result<PocketTarExtractionReport> =
    withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val storage = StorageRepository(appContext)
        var extractionRoot: DocumentFile? = null

        try {
            val mount = storage.ensurePocketDrive().getOrThrow()
            val downloadsUri =
                requireNotNull(mount.uriFor(PocketDriveDirectory.DOWNLOADS)) {
                    "P:\\Downloads não está disponível."
                }
            val downloads = resolveTarDirectory(appContext, downloadsUri)
            check(downloads.canWrite()) {
                "P:\\Downloads não permite escrita."
            }

            val requestedFolder =
                archiveFileName
                    .removeSuffixIgnoreCase(".tar")
                    .let(::sanitizePocketImportedFileName)
                    .ifBlank { "TAR extraído" }
            val destinationName =
                uniqueTarChildName(downloads, requestedFolder)
            val root =
                checkNotNull(downloads.createDirectory(destinationName)) {
                    "Não foi possível criar P:\\Downloads\\$destinationName."
                }
            extractionRoot = root

            var entryCount = 0
            var fileCount = 0
            var directoryCount = 0
            var extractedBytes = 0L

            openTarInput(appContext, uriString).use { input ->
                while (true) {
                    val header = readTarHeader(input) ?: break
                    entryCount++
                    check(entryCount <= MAX_TAR_ENTRY_COUNT) {
                        "O TAR ultrapassa o limite de $MAX_TAR_ENTRY_COUNT entradas."
                    }

                    val segments = validatePocketTarEntryPath(header.path)
                    if (header.directory) {
                        ensureTarDirectories(root, segments)
                        directoryCount++
                    } else {
                        check(header.size <= MAX_TAR_FILE_BYTES) {
                            "A entrada ${header.path} ultrapassa 512 MiB."
                        }
                        check(extractedBytes <= MAX_TAR_TOTAL_BYTES - header.size) {
                            "A extração ultrapassa o limite total de 1 GiB do PocketPC."
                        }

                        val parent =
                            ensureTarDirectories(
                                root = root,
                                segments = segments.dropLast(1),
                            )
                        val requestedName = segments.last()
                        val targetName = uniqueTarChildName(parent, requestedName)
                        var target: DocumentFile? = null

                        try {
                            val outputDocument =
                                checkNotNull(
                                    parent.createFile(
                                        pocketTarMimeType(targetName),
                                        targetName,
                                    )
                                ) {
                                    "O PocketDrive recusou criar $targetName."
                                }
                            target = outputDocument

                            appContext.contentResolver
                                .openOutputStream(outputDocument.uri, "w")
                                ?.buffered(64 * 1024)
                                ?.use { output ->
                                    copyExactTarBytes(
                                        input = input,
                                        output = output,
                                        byteCount = header.size,
                                    )
                                    output.flush()
                                }
                                ?: error("Não foi possível gravar $targetName.")

                            extractedBytes += header.size
                            fileCount++
                        } catch (error: Throwable) {
                            runCatching { target?.delete() }
                            throw error
                        }
                    }

                    if (header.directory) {
                        consumeExactTarBytes(input, header.size)
                    }
                    consumeExactTarBytes(input, tarPadding(header.size))
                }
            }

            Result.success(
                PocketTarExtractionReport(
                    destinationName = destinationName,
                    fileCount = fileCount,
                    directoryCount = directoryCount,
                    extractedBytes = extractedBytes,
                )
            )
        } catch (error: Throwable) {
            runCatching { extractionRoot?.delete() }
            Result.failure(error)
        }
    }

private data class TarHeader(
    val path: String,
    val size: Long,
    val directory: Boolean,
)

private fun readTarHeader(input: InputStream): TarHeader? {
    val block = ByteArray(TAR_BLOCK_SIZE)
    val read = readFullyOrEnd(input, block)
    if (read == 0) return null
    check(read == TAR_BLOCK_SIZE) {
        "Cabeçalho TAR truncado."
    }
    if (block.all { it == 0.toByte() }) return null

    validateTarChecksum(block)

    val name = tarString(block, 0, 100)
    val prefix = tarString(block, 345, 155)
    val path =
        when {
            prefix.isNotBlank() && name.isNotBlank() -> "$prefix/$name"
            prefix.isNotBlank() -> prefix
            else -> name
        }
    val size = parseTarOctal(block, 124, 12, "tamanho")
    val type = block[156].toInt().toChar()

    val directory =
        when (type) {
            '\u0000', '0' -> false
            '5' -> true
            '1', '2' ->
                error("Links dentro de TAR não são extraídos pelo PocketPC por segurança.")
            '3', '4', '6' ->
                error("Entrada especial de dispositivo/FIFO em TAR não é suportada.")
            'x', 'g', 'L', 'K' ->
                error("Metadados PAX/GNU long-name ainda não são suportados pelo leitor TAR interno.")
            else ->
                error("Tipo de entrada TAR não suportado: ${type.code}.")
        }

    if (directory) {
        check(size == 0L) {
            "Diretório TAR com payload inesperado foi recusado."
        }
    }

    return TarHeader(
        path = path,
        size = size,
        directory = directory,
    )
}

private fun validateTarChecksum(block: ByteArray) {
    val expected = parseTarOctal(block, 148, 8, "checksum")
    var actual = 0L
    block.forEachIndexed { index, byte ->
        actual +=
            if (index in 148..155) {
                32
            } else {
                byte.toInt() and 0xFF
            }
    }
    check(actual == expected) {
        "Checksum TAR inválido."
    }
}

private fun parseTarOctal(
    block: ByteArray,
    offset: Int,
    length: Int,
    field: String,
): Long {
    check((block[offset].toInt() and 0x80) == 0) {
        "Campo TAR $field em formato base-256 ainda não é suportado."
    }
    val raw =
        block.copyOfRange(offset, offset + length)
            .toString(Charsets.US_ASCII)
            .trim('\u0000', ' ')
    if (raw.isEmpty()) return 0L
    check(raw.all { it in '0'..'7' }) {
        "Campo TAR $field inválido."
    }
    return raw.toLongOrNull(8)
        ?: error("Campo TAR $field excede o intervalo suportado.")
}

private fun tarString(
    block: ByteArray,
    offset: Int,
    length: Int,
): String =
    block.copyOfRange(offset, offset + length)
        .takeWhile { it != 0.toByte() }
        .toByteArray()
        .toString(Charsets.UTF_8)
        .trim()

internal fun validatePocketTarEntryPath(raw: String): List<String> {
    require(raw.isNotBlank()) {
        "O TAR contém uma entrada sem nome."
    }
    require(raw.length <= MAX_TAR_PATH_LENGTH) {
        "O TAR contém um caminho maior que $MAX_TAR_PATH_LENGTH caracteres."
    }
    require('\u0000' !in raw) {
        "O TAR contém NUL no caminho."
    }

    val normalized = raw.replace('\\', '/')
    require(!normalized.startsWith('/')) {
        "O TAR contém um caminho absoluto."
    }
    require(!Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
        "O TAR contém um caminho absoluto do Windows."
    }

    val segments = normalized.split('/').filter { it.isNotEmpty() }
    require(segments.isNotEmpty()) {
        "O TAR contém uma entrada sem destino válido."
    }
    require(segments.size <= MAX_TAR_PATH_DEPTH) {
        "O TAR ultrapassa $MAX_TAR_PATH_DEPTH níveis de pastas."
    }

    segments.forEach { segment ->
        require(segment != "." && segment != "..") {
            "O TAR tentou sair da pasta de extração."
        }
        require(segment.length <= MAX_TAR_SEGMENT_LENGTH) {
            "O TAR contém um nome muito longo."
        }
        require(segment.none { it.code < 32 }) {
            "O TAR contém caracteres de controle no nome."
        }
        require(segment.none { it in WINDOWS_FORBIDDEN_TAR_NAME_CHARS }) {
            "O TAR contém caracteres incompatíveis com o PocketDrive."
        }
        require(segment == segment.trimEnd('.', ' ')) {
            "O TAR contém nome terminado em ponto ou espaço."
        }
        val base = segment.substringBefore('.').uppercase()
        require(base !in WINDOWS_RESERVED_TAR_NAMES) {
            "O TAR contém nome reservado do Windows: $segment."
        }
        validateStorageName(segment)
    }

    return segments
}

private fun openTarInput(
    context: Context,
    uriString: String,
): BufferedInputStream {
    val input =
        context.contentResolver
            .openInputStream(Uri.parse(uriString))
            ?: error("Não foi possível abrir o TAR.")
    return BufferedInputStream(input, 64 * 1024)
}

private fun readFullyOrEnd(
    input: InputStream,
    buffer: ByteArray,
): Int {
    var offset = 0
    while (offset < buffer.size) {
        val count = input.read(buffer, offset, buffer.size - offset)
        if (count < 0) break
        if (count == 0) continue
        offset += count
    }
    return offset
}

private fun consumeExactTarBytes(
    input: InputStream,
    byteCount: Long,
) {
    var remaining = byteCount
    val buffer = ByteArray(64 * 1024)
    while (remaining > 0L) {
        val count =
            input.read(
                buffer,
                0,
                minOf(buffer.size.toLong(), remaining).toInt(),
            )
        check(count >= 0) {
            "TAR truncado durante leitura do conteúdo."
        }
        if (count == 0) continue
        remaining -= count.toLong()
    }
}

private fun copyExactTarBytes(
    input: InputStream,
    output: java.io.OutputStream,
    byteCount: Long,
) {
    var remaining = byteCount
    val buffer = ByteArray(64 * 1024)
    while (remaining > 0L) {
        val count =
            input.read(
                buffer,
                0,
                minOf(buffer.size.toLong(), remaining).toInt(),
            )
        check(count >= 0) {
            "TAR truncado durante extração."
        }
        if (count == 0) continue
        output.write(buffer, 0, count)
        remaining -= count.toLong()
    }
}

private fun tarPadding(size: Long): Long =
    (TAR_BLOCK_SIZE - (size % TAR_BLOCK_SIZE)) % TAR_BLOCK_SIZE

private fun resolveTarDirectory(
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

private fun ensureTarDirectories(
    root: DocumentFile,
    segments: List<String>,
): DocumentFile {
    var current = root
    for (segment in segments) {
        val existing = current.findFile(segment)
        current =
            when {
                existing == null ->
                    checkNotNull(current.createDirectory(segment)) {
                        "Não foi possível criar a pasta $segment."
                    }
                existing.isDirectory -> existing
                else -> error("Conflito entre arquivo e pasta em $segment.")
            }
    }
    return current
}

private fun uniqueTarChildName(
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
        val candidate = base.take(150) + suffix + extension
        if (parent.findFile(candidate) == null) return candidate
    }
    error("Não foi possível gerar um nome único para $requested.")
}

private fun String.removeSuffixIgnoreCase(suffix: String): String =
    if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this

private fun pocketTarMimeType(name: String): String =
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
        "tar" -> "application/x-tar"
        else -> "application/octet-stream"
    }

private val WINDOWS_FORBIDDEN_TAR_NAME_CHARS =
    setOf('<', '>', ':', '"', '|', '?', '*')

private val WINDOWS_RESERVED_TAR_NAMES =
    buildSet {
        addAll(setOf("CON", "PRN", "AUX", "NUL"))
        (1..9).forEach { index ->
            add("COM$index")
            add("LPT$index")
        }
    }
