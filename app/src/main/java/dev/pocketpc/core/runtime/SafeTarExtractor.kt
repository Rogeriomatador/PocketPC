package dev.pocketpc.core.runtime

import java.io.BufferedWriter
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.TreeSet

data class ExtractionPolicy(
    val maxEntries: Int,
    val maxExtractedBytes: Long,
    val maxExtendedHeaderBytes: Long = 1024L * 1024L,
    val maxPathBytes: Int = 4096,
)

data class ExtractionStats(
    val entries: Int,
    val regularFiles: Int,
    val directories: Int,
    val linksRecorded: Int,
    val extractedBytes: Long,
)

class ArchiveSecurityException(message: String) : IOException(message)

/**
 * Strict TAR extractor for rootfs data.
 *
 * Links are recorded as metadata and never materialized in Android's filesystem.
 * A future Linux substrate must interpret the metadata inside guest semantics.
 */
object SafeTarExtractor {
    private const val BLOCK_SIZE = 512
    private val base64 = Base64.getUrlEncoder().withoutPadding()

    fun extract(
        input: InputStream,
        destination: File,
        metadataFile: File,
        policy: ExtractionPolicy,
    ): ExtractionStats {
        require(policy.maxEntries > 0)
        require(policy.maxExtractedBytes > 0L)
        require(policy.maxExtendedHeaderBytes in 1..(16L * 1024L * 1024L))
        require(policy.maxPathBytes in 64..16384)

        require(destination.mkdirs() || destination.isDirectory) {
            "Não foi possível criar o diretório de extração."
        }
        val root = destination.canonicalFile
        metadataFile.parentFile?.let { parent ->
            require(parent.mkdirs() || parent.isDirectory) {
                "Não foi possível criar diretório de metadados."
            }
        }
        require(!metadataFile.exists()) { "Arquivo de metadados já existe." }

        var entries = 0
        var regularFiles = 0
        var directories = 0
        var links = 0
        var extractedBytes = 0L
        var zeroBlocks = 0

        val seen = TreeSet<String>()
        val linkPaths = HashSet<String>()
        val globalPax = LinkedHashMap<String, String>()
        var localPax = emptyMap<String, String>()
        var pendingLongName: String? = null
        var pendingLongLink: String? = null

        BufferedWriter(
            OutputStreamWriter(FileOutputStream(metadataFile), StandardCharsets.UTF_8)
        ).use { metadata ->
            while (true) {
                val block = readBlock(input)
                if (block == null) {
                    if (zeroBlocks >= 2) break
                    throw EOFException("TAR terminou sem dois blocos zero.")
                }

                if (block.all { it.toInt() == 0 }) {
                    zeroBlocks++
                    if (zeroBlocks >= 2) break
                    continue
                }
                zeroBlocks = 0

                validateChecksum(block)
                val rawName = headerString(block, 0, 100)
                val prefix = headerString(block, 345, 155)
                val headerPath = if (prefix.isBlank()) rawName else "$prefix/$rawName"
                val type = block[156].toInt().toChar()
                val headerLink = headerString(block, 157, 100)
                val headerSize = parseOctal(block, 124, 12, "size")
                val mode = parseOctal(block, 100, 8, "mode")
                val uid = parseOctal(block, 108, 8, "uid")
                val gid = parseOctal(block, 116, 8, "gid")
                val mtime = parseOctal(block, 136, 12, "mtime")

                when (type) {
                    'x', 'g' -> {
                        require(headerSize <= policy.maxExtendedHeaderBytes) {
                            "PAX header excede o limite."
                        }
                        val data = readEntryBytes(input, headerSize)
                        skipPadding(input, headerSize)
                        val parsed = parsePax(data)
                        if (type == 'g') globalPax.putAll(parsed) else localPax = parsed
                        continue
                    }
                    'L', 'K' -> {
                        require(headerSize <= policy.maxExtendedHeaderBytes) {
                            "GNU long header excede o limite."
                        }
                        val value = String(readEntryBytes(input, headerSize), StandardCharsets.UTF_8)
                            .trimEnd('\u0000', '\n')
                        skipPadding(input, headerSize)
                        if (type == 'L') pendingLongName = value else pendingLongLink = value
                        continue
                    }
                }

                entries++
                if (entries > policy.maxEntries) {
                    throw ArchiveSecurityException("TAR excedeu o limite de entradas.")
                }

                val pax = LinkedHashMap<String, String>().apply {
                    putAll(globalPax)
                    putAll(localPax)
                }
                localPax = emptyMap()

                val effectiveSize = pax["size"]?.toLongOrNull() ?: headerSize
                if (effectiveSize < 0L) throw ArchiveSecurityException("Tamanho TAR negativo.")

                val requestedPath = pax["path"] ?: pendingLongName ?: headerPath
                val requestedLink = pax["linkpath"] ?: pendingLongLink ?: headerLink
                pendingLongName = null
                pendingLongLink = null

                val path = normalizeArchivePath(requestedPath, policy.maxPathBytes)
                if (path.isEmpty()) {
                    if (type == '5') {
                        skipEntryPayload(input, effectiveSize)
                        continue
                    }
                    throw ArchiveSecurityException("Entrada TAR sem caminho.")
                }

                ensureNoLinkAncestor(path, linkPaths)
                if (!seen.add(path)) {
                    throw ArchiveSecurityException("Caminho TAR duplicado: $path")
                }

                when (type) {
                    '\u0000', '0', '7' -> {
                        extractedBytes = Math.addExact(extractedBytes, effectiveSize)
                        if (extractedBytes > policy.maxExtractedBytes) {
                            throw ArchiveSecurityException("Bytes extraídos excederam o limite.")
                        }

                        val outputFile = safeDestination(root, path)
                        ensureParentDirectories(root, outputFile.parentFile)
                        FileOutputStream(outputFile).use { output ->
                            copyExactly(input, output, effectiveSize)
                            output.fd.sync()
                        }
                        skipPadding(input, effectiveSize)
                        regularFiles++
                        writeMetadata(
                            metadata, "F", path, "", mode, uid, gid, effectiveSize, mtime
                        )
                    }

                    '5' -> {
                        if (hasDescendant(seen, path)) {
                            throw ArchiveSecurityException("Diretório apareceu depois de descendente: $path")
                        }
                        val dir = safeDestination(root, path)
                        if (!dir.mkdirs() && !dir.isDirectory) {
                            throw IOException("Não foi possível criar diretório: $path")
                        }
                        skipEntryPayload(input, effectiveSize)
                        directories++
                        writeMetadata(metadata, "D", path, "", mode, uid, gid, 0L, mtime)
                    }

                    '2', '1' -> {
                        if (hasDescendant(seen, path)) {
                            throw ArchiveSecurityException("Link apareceu depois de descendente: $path")
                        }
                        validateLinkTarget(requestedLink, policy.maxPathBytes)
                        ensureParentDirectories(root, safeDestination(root, path).parentFile)
                        skipEntryPayload(input, effectiveSize)
                        linkPaths += path
                        links++
                        writeMetadata(
                            metadata,
                            if (type == '2') "S" else "H",
                            path,
                            requestedLink,
                            mode,
                            uid,
                            gid,
                            0L,
                            mtime,
                        )
                    }

                    else -> throw ArchiveSecurityException(
                        "Tipo TAR não permitido: '$type' em $path"
                    )
                }
            }
        }

        return ExtractionStats(
            entries = entries,
            regularFiles = regularFiles,
            directories = directories,
            linksRecorded = links,
            extractedBytes = extractedBytes,
        )
    }

    private fun hasDescendant(seen: TreeSet<String>, path: String): Boolean {
        val prefix = "$path/"
        val candidate = seen.ceiling(prefix) ?: return false
        return candidate.startsWith(prefix)
    }

    private fun validateChecksum(block: ByteArray) {
        val expected = parseOctal(block, 148, 8, "checksum")
        var actual = 0L
        for (index in block.indices) {
            actual += if (index in 148..155) 0x20 else (block[index].toInt() and 0xff)
        }
        if (expected != actual) throw ArchiveSecurityException("Checksum TAR inválido.")
    }

    private fun parseOctal(
        block: ByteArray,
        offset: Int,
        length: Int,
        field: String,
    ): Long {
        if ((block[offset].toInt() and 0x80) != 0) {
            throw ArchiveSecurityException("Formato numérico base-256 não suportado em $field.")
        }
        val raw = String(block, offset, length, StandardCharsets.US_ASCII).trim('\u0000', ' ')
        if (raw.isEmpty()) return 0L
        if (!raw.all { it in '0'..'7' }) {
            throw ArchiveSecurityException("Campo TAR octal inválido: $field.")
        }
        return raw.toLong(8)
    }

    private fun headerString(block: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && block[end].toInt() != 0) end++
        return String(block, offset, end - offset, StandardCharsets.UTF_8)
    }

    private fun normalizeArchivePath(raw: String, maxPathBytes: Int): String {
        if (raw.indexOf('\u0000') >= 0) throw ArchiveSecurityException("NUL em caminho TAR.")
        if (raw.startsWith("/") || raw.startsWith("\\")) {
            throw ArchiveSecurityException("Caminho TAR absoluto não permitido: $raw")
        }
        if ('\\' in raw) throw ArchiveSecurityException("Backslash em caminho TAR não permitido.")
        if (raw.toByteArray(StandardCharsets.UTF_8).size > maxPathBytes) {
            throw ArchiveSecurityException("Caminho TAR excede o limite.")
        }

        val parts = ArrayList<String>()
        for (part in raw.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> throw ArchiveSecurityException("Path traversal TAR detectado: $raw")
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private fun validateLinkTarget(target: String, maxPathBytes: Int) {
        if (target.indexOf('\u0000') >= 0) throw ArchiveSecurityException("NUL em target de link.")
        if (target.toByteArray(StandardCharsets.UTF_8).size > maxPathBytes) {
            throw ArchiveSecurityException("Target de link excede o limite.")
        }
    }

    private fun ensureNoLinkAncestor(path: String, linkPaths: Set<String>) {
        var index = path.indexOf('/')
        while (index >= 0) {
            if (path.substring(0, index) in linkPaths) {
                throw ArchiveSecurityException("Entrada abaixo de link TAR: $path")
            }
            index = path.indexOf('/', index + 1)
        }
    }

    private fun safeDestination(root: File, path: String): File {
        val target = File(root, path).canonicalFile
        if (target != root && !target.path.startsWith(root.path + File.separator)) {
            throw ArchiveSecurityException("Destino escapou do rootfs: $path")
        }
        return target
    }

    private fun ensureParentDirectories(root: File, parent: File?) {
        if (parent == null) return
        val canonicalParent = parent.canonicalFile
        if (canonicalParent != root && !canonicalParent.path.startsWith(root.path + File.separator)) {
            throw ArchiveSecurityException("Diretório pai escapou do rootfs.")
        }
        if (!canonicalParent.mkdirs() && !canonicalParent.isDirectory) {
            throw IOException("Não foi possível criar diretório pai.")
        }
    }

    private fun parsePax(data: ByteArray): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var offset = 0

        while (offset < data.size) {
            var space = offset
            while (space < data.size && data[space] != ' '.code.toByte()) space++
            if (space == offset || space >= data.size) {
                throw ArchiveSecurityException("Registro PAX inválido.")
            }

            val lengthText = String(data, offset, space - offset, StandardCharsets.US_ASCII)
            val length = lengthText.toIntOrNull()
                ?: throw ArchiveSecurityException("Comprimento PAX inválido.")
            if (length <= 0 || offset + length > data.size || space >= offset + length) {
                throw ArchiveSecurityException("Comprimento PAX fora dos limites.")
            }

            var recordEnd = offset + length
            if (data[recordEnd - 1] == '\n'.code.toByte()) recordEnd--
            val record = String(
                data,
                space + 1,
                recordEnd - (space + 1),
                StandardCharsets.UTF_8,
            )
            val equals = record.indexOf('=')
            if (equals > 0) result[record.substring(0, equals)] = record.substring(equals + 1)
            offset += length
        }
        return result
    }

    private fun readEntryBytes(input: InputStream, size: Long): ByteArray {
        if (size > Int.MAX_VALUE) throw ArchiveSecurityException("Header estendido grande demais.")
        val result = ByteArray(size.toInt())
        readFully(input, result, 0, result.size)
        return result
    }

    private fun skipEntryPayload(input: InputStream, size: Long) {
        skipExactly(input, size)
        skipPadding(input, size)
    }

    private fun copyExactly(input: InputStream, output: FileOutputStream, size: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = size
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("TAR truncado durante arquivo.")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val padding = (BLOCK_SIZE - (size % BLOCK_SIZE)) % BLOCK_SIZE
        skipExactly(input, padding)
    }

    private fun skipExactly(input: InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("TAR truncado.")
            remaining -= read
        }
    }

    private fun readBlock(input: InputStream): ByteArray? {
        val block = ByteArray(BLOCK_SIZE)
        var offset = 0
        while (offset < block.size) {
            val read = input.read(block, offset, block.size - offset)
            if (read < 0) {
                if (offset == 0) return null
                throw EOFException("Bloco TAR truncado.")
            }
            offset += read
        }
        return block
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            val read = input.read(buffer, offset + done, length - done)
            if (read < 0) throw EOFException("TAR truncado.")
            done += read
        }
    }

    private fun writeMetadata(
        metadata: BufferedWriter,
        type: String,
        path: String,
        target: String,
        mode: Long,
        uid: Long,
        gid: Long,
        size: Long,
        mtime: Long,
    ) {
        metadata.append(type).append('\t')
            .append(mode.toString()).append('\t')
            .append(uid.toString()).append('\t')
            .append(gid.toString()).append('\t')
            .append(size.toString()).append('\t')
            .append(mtime.toString()).append('\t')
            .append(encode(path)).append('\t')
            .append(encode(target))
            .append('\n')
    }

    private fun encode(value: String): String =
        base64.encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}
