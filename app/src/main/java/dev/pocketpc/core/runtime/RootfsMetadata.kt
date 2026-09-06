package dev.pocketpc.core.runtime

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

enum class RootfsEntryType {
    FILE,
    DIRECTORY,
    SYMLINK,
    HARDLINK,
}

data class RootfsMetadataEntry(
    val type: RootfsEntryType,
    val mode: Long,
    val uid: Long,
    val gid: Long,
    val size: Long,
    val mtime: Long,
    val path: String,
    val target: String,
)

object RootfsMetadata {
    private val decoder = Base64.getUrlDecoder()

    fun read(file: File, maxEntries: Int): List<RootfsMetadataEntry> {
        require(maxEntries > 0)
        require(file.isFile) { "rootfs metadata não existe." }

        val entries = ArrayList<RootfsMetadataEntry>()
        val seen = HashSet<String>()

        file.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.forEachIndexed { index, line ->
                if (entries.size >= maxEntries) {
                    throw ArchiveSecurityException("Metadata excedeu o limite de entradas.")
                }

                val fields = line.split('\t')
                if (fields.size != 8) {
                    throw ArchiveSecurityException("Metadata inválida na linha ${index + 1}.")
                }

                val type = when (fields[0]) {
                    "F" -> RootfsEntryType.FILE
                    "D" -> RootfsEntryType.DIRECTORY
                    "S" -> RootfsEntryType.SYMLINK
                    "H" -> RootfsEntryType.HARDLINK
                    else -> throw ArchiveSecurityException(
                        "Tipo de metadata inválido na linha ${index + 1}."
                    )
                }

                val path = decode(fields[6], "path", index)
                val target = decode(fields[7], "target", index)
                val normalized = GuestPath.normalizeArchivePath(path)
                    ?: throw ArchiveSecurityException("Path de metadata inválido: $path")
                if (normalized != path) {
                    throw ArchiveSecurityException("Path de metadata não normalizado: $path")
                }
                if (!seen.add(path)) {
                    throw ArchiveSecurityException("Path duplicado em metadata: $path")
                }

                entries += RootfsMetadataEntry(
                    type = type,
                    mode = fields[1].toLongOrNull()
                        ?: throw ArchiveSecurityException("mode inválido na linha ${index + 1}."),
                    uid = fields[2].toLongOrNull()
                        ?: throw ArchiveSecurityException("uid inválido na linha ${index + 1}."),
                    gid = fields[3].toLongOrNull()
                        ?: throw ArchiveSecurityException("gid inválido na linha ${index + 1}."),
                    size = fields[4].toLongOrNull()
                        ?: throw ArchiveSecurityException("size inválido na linha ${index + 1}."),
                    mtime = fields[5].toLongOrNull()
                        ?: throw ArchiveSecurityException("mtime inválido na linha ${index + 1}."),
                    path = path,
                    target = target,
                )
            }
        }

        return entries
    }

    private fun decode(value: String, field: String, index: Int): String =
        try {
            String(decoder.decode(value), StandardCharsets.UTF_8)
        } catch (error: IllegalArgumentException) {
            throw ArchiveSecurityException(
                "Base64 inválido em $field na linha ${index + 1}."
            )
        }
}

object GuestPath {
    fun normalizeArchivePath(raw: String): String? {
        if (raw.isEmpty() || raw.startsWith("/") || '\u0000' in raw || '\\' in raw) return null
        val parts = ArrayList<String>()
        raw.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> return null
                else -> parts += part
            }
        }
        return parts.joinToString("/").takeIf { it.isNotEmpty() }
    }

    fun normalizeAbsolute(raw: String): String? {
        if (!raw.startsWith("/") || '\u0000' in raw || '\\' in raw) return null
        val stack = ArrayDeque<String>()
        raw.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isEmpty()) return null else stack.removeLast()
                else -> stack.add(part)
            }
        }
        return "/" + stack.joinToString("/")
    }

    fun resolveSymlinkTarget(linkPath: String, target: String): String? {
        val link = normalizeArchivePath(linkPath) ?: return null
        if ('\u0000' in target || '\\' in target || target.isEmpty()) return null

        val combined = if (target.startsWith("/")) {
            target
        } else {
            val parent = link.substringBeforeLast('/', missingDelimiterValue = "")
            "/" + listOf(parent, target)
                .filter { it.isNotEmpty() }
                .joinToString("/")
        }
        return normalizeAbsolute(combined)
    }

    fun hardlinkTarget(target: String): String? =
        normalizeArchivePath(target)
}
