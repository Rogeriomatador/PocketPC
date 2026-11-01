package dev.pocketpc.core.runtime

import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

data class PcProgramDocument(val id: String, val name: String, val directory: Boolean, val size: Long = -1)

interface PcProgramDirectorySource {
    val rootId: String
    fun children(directoryId: String): List<PcProgramDocument>
    fun open(documentId: String): InputStream
}

data class ImportedPcProgram(
    val directory: File,
    val executable: File,
    val relativePath: String,
    val bytes: Long,
    val sha256: String,
    val fileCount: Int,
    val totalBytes: Long,
)

/** Copies into private staging and publishes only a complete tree containing the selected EXE/MSI. */
class PcProgramDirectoryImporter(
    private val maxBytes: Long = 16L * 1024 * 1024 * 1024,
    private val maxEntries: Int = 20_000,
    private val maxDepth: Int = 32,
) {
    init { require(maxBytes > 0 && maxEntries > 0 && maxDepth > 0) }

    fun importProgram(source: PcProgramDirectorySource, selectedId: String, expectedName: String,
                      expectedBytes: Long, targetRoot: File,
                      checkActive: () -> Unit = {}): Result<ImportedPcProgram> = runCatching {
        require(expectedBytes in 0..maxBytes) { "PC_PROGRAM_DECLARED_SIZE_INVALID" }
        require(source.rootId.isNotBlank()) { "PC_PROGRAM_ROOT_ID_INVALID" }
        require(SafeTreeOps.isPlainDirectory(targetRoot.toPath())) { "PC_PROGRAM_ROOT_INVALID" }
        val root = targetRoot.canonicalFile
        val staging = Files.createTempDirectory(root.toPath(), ".program-").toFile()
        var published: File? = null
        var selectedPath: String? = null
        var selectedDigest: String? = null
        var selectedBytes = 0L
        var bytes = 0L
        var count = 0
        var fileCount = 0
        val visited = hashSetOf(source.rootId)
        try {
            fun copy(directoryId: String, destination: File, relative: String, depth: Int) {
                checkActive()
                require(depth <= maxDepth) { "PC_PROGRAM_DEPTH_LIMIT" }
                val names = hashSetOf<String>()
                for (entry in source.children(directoryId)) {
                    checkActive()
                    require(++count <= maxEntries) { "PC_PROGRAM_ENTRY_LIMIT" }
                    require(entry.id.isNotBlank() && visited.add(entry.id)) { "PC_PROGRAM_DOCUMENT_REPEATED" }
                    require(validName(entry.name)) { "PC_PROGRAM_NAME_INVALID" }
                    require(names.add(entry.name.lowercase(Locale.ROOT))) { "PC_PROGRAM_NAME_COLLISION" }
                    val child = File(destination, entry.name)
                    require(child.canonicalFile.parentFile == destination.canonicalFile) { "PC_PROGRAM_PATH_ESCAPED" }
                    val path = if (relative.isEmpty()) entry.name else "$relative/${entry.name}"
                    if (entry.directory) {
                        require(entry.id != selectedId) { "PC_PROGRAM_TARGET_IS_DIRECTORY" }
                        Files.createDirectory(child.toPath())
                        copy(entry.id, child, path, depth + 1)
                    } else {
                        require(entry.size < 0 || entry.size <= maxBytes - bytes) { "PC_PROGRAM_SIZE_LIMIT" }
                        val digest = if (entry.id == selectedId) MessageDigest.getInstance("SHA-256") else null
                        var copied = 0L
                        source.open(entry.id).use { input ->
                            Files.newOutputStream(child.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW).use { output ->
                                val buffer = ByteArray(128 * 1024)
                                while (true) {
                                    checkActive()
                                    if (Thread.currentThread().isInterrupted) error("PC_PROGRAM_COPY_INTERRUPTED")
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    if (read == 0) continue
                                    require(read.toLong() <= maxBytes - bytes) { "PC_PROGRAM_SIZE_LIMIT" }
                                    copied += read; bytes += read
                                    digest?.update(buffer, 0, read)
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                        require(entry.size < 0 || entry.size == copied) { "PC_PROGRAM_SOURCE_SIZE_CHANGED" }
                        fileCount++
                        if (entry.id == selectedId) {
                            require(entry.name == expectedName) { "PC_PROGRAM_TARGET_NAME_CHANGED" }
                            require(copied > 0 && (expectedBytes == 0L || expectedBytes == copied)) { "PC_PROGRAM_TARGET_SIZE_CHANGED" }
                            selectedPath = path; selectedBytes = copied; selectedDigest = requireNotNull(digest).digest().toHex()
                        }
                    }
                }
            }
            require(selectedId != source.rootId) { "PC_PROGRAM_TARGET_IS_DIRECTORY" }
            copy(source.rootId, staging, "", 0)
            val path = selectedPath ?: error("PC_PROGRAM_TARGET_NOT_IN_FOLDER")
            checkActive()
            val destination = File(root, "program-${UUID.randomUUID()}")
            try {
                Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging.toPath(), destination.toPath())
            }
            published = destination
            val executable = File(destination, path)
            require(SafeTreeOps.isPlainFile(executable.toPath()) && executable.length() == selectedBytes) {
                "PC_PROGRAM_FINAL_TARGET_INVALID"
            }
            ImportedPcProgram(destination, executable, path, selectedBytes,
                requireNotNull(selectedDigest), fileCount, bytes)
        } catch (failure: Throwable) {
            published?.let(SafeTreeOps::deleteNoFollow)
            throw failure
        } finally {
            SafeTreeOps.deleteNoFollow(staging)
        }
    }

    private fun validName(name: String): Boolean {
        if (name.isEmpty() || name.length > 240 || name.endsWith('.') || name.endsWith(' ') ||
            name.any { it.code < 32 || it in "/\\:<>\"|?*" }) return false
        val base = name.substringBefore('.').uppercase(Locale.ROOT)
        return base !in setOf("CON", "PRN", "AUX", "NUL") && !Regex("(COM|LPT)[1-9]").matches(base)
    }
}
