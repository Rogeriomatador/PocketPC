package dev.pocketpc.core.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

data class LinkPreparationResult(
    val prepared: Boolean,
    val symlinks: Int,
    val hardlinks: Int,
    val metadataSha256: String?,
    val message: String,
)

class RootfsLinkManager {
    suspend fun prepare(runtime: InstalledRuntime): LinkPreparationResult =
        withContext(Dispatchers.IO) {
            prepareBlocking(runtime)
        }

    private fun prepareBlocking(runtime: InstalledRuntime): LinkPreparationResult {
        val marker = File(runtime.directory, MARKER_NAME)
        if (marker.isFile) return verify(runtime)

        val entries = runCatching {
            RootfsMetadata.read(runtime.metadataFile, runtime.manifest.entryLimit)
        }.getOrElse {
            return failed("METADATA_INVALID: ${it.message}")
        }

        val links = entries.filter {
            it.type == RootfsEntryType.SYMLINK || it.type == RootfsEntryType.HARDLINK
        }
        if (links.size != runtime.stats.linksRecorded) {
            return failed("Contagem de links difere de INSTALL_VERIFIED.")
        }

        val root = runtime.rootfsData.toPath().toAbsolutePath().normalize()
        val entryMap = entries.associateBy { it.path }

        val recoveryError = recoverInterruptedPreparation(runtime, root, links)
        if (recoveryError != null) return failed(recoveryError)

        val validationError = validatePlan(root, links, entryMap)
        if (validationError != null) return failed(validationError)

        val metadataDigest = runtime.metadataFile.inputStream().buffered().use {
            Sha256.digest(it, maxBytes = MAX_METADATA_BYTES)
        }

        val preparing = File(runtime.directory, PREPARING_MARKER_NAME)
        preparing.writeText(
            "schema=1\nmetadataSha256=${metadataDigest.sha256}\n"
        )

        val created = ArrayList<Path>()
        var hardlinks = 0
        var symlinks = 0

        try {
            links.filter { it.type == RootfsEntryType.HARDLINK }.forEach { entry ->
                val linkPath = root.resolve(entry.path).normalize()
                val targetRelative = resolveHardlinkTarget(entry, entryMap)
                    ?: throw ArchiveSecurityException(
                        "Hardlink sem target regular: ${entry.path}"
                    )
                val targetPath = root.resolve(targetRelative).normalize()
                Files.createLink(linkPath, targetPath)
                created.add(linkPath)
                hardlinks++
            }

            links.filter { it.type == RootfsEntryType.SYMLINK }.forEach { entry ->
                val linkPath = root.resolve(entry.path).normalize()
                Files.createSymbolicLink(linkPath, Paths.get(entry.target))
                created.add(linkPath)
                symlinks++
            }

            marker.writeText(
                buildString {
                    appendLine("schema=1")
                    appendLine("metadataSha256=${metadataDigest.sha256}")
                    appendLine("metadataBytes=${metadataDigest.bytes}")
                    appendLine("symlinks=$symlinks")
                    appendLine("hardlinks=$hardlinks")
                    appendLine("guestSemantics=preserve-link-target")
                    appendLine("deletion=nofollow")
                }
            )
            preparing.delete()

            return verify(runtime.copy(linksPrepared = true))
        } catch (error: Throwable) {
            created.asReversed().forEach { path ->
                runCatching { Files.deleteIfExists(path) }
            }
            preparing.delete()
            marker.delete()
            return failed(
                "LINK_PREPARATION_FAILED: ${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    fun verify(runtime: InstalledRuntime): LinkPreparationResult {
        val marker = File(runtime.directory, MARKER_NAME)
        if (!marker.isFile) return failed("LINKS_NOT_PREPARED")

        return runCatching {
            val expectedHash = markerValue(marker, "metadataSha256")
                ?: error("metadataSha256 ausente.")
            val expectedSymlinks = markerValue(marker, "symlinks")?.toIntOrNull()
                ?: error("symlinks ausente/inválido.")
            val expectedHardlinks = markerValue(marker, "hardlinks")?.toIntOrNull()
                ?: error("hardlinks ausente/inválido.")

            val entries = RootfsMetadata.read(
                runtime.metadataFile,
                runtime.manifest.entryLimit,
            )
            val entryMap = entries.associateBy { it.path }
            val actual = runtime.metadataFile.inputStream().buffered().use {
                Sha256.digest(it, maxBytes = MAX_METADATA_BYTES)
            }
            require(actual.sha256 == expectedHash) {
                "Metadata mudou após preparação."
            }

            val root = runtime.rootfsData.toPath().toAbsolutePath().normalize()
            var symlinks = 0
            var hardlinks = 0

            entries.forEach { entry ->
                val path = root.resolve(entry.path).normalize()
                when (entry.type) {
                    RootfsEntryType.SYMLINK -> {
                        require(Files.isSymbolicLink(path)) {
                            "Symlink ausente: ${entry.path}"
                        }
                        require(
                            Files.readSymbolicLink(path).normalize() ==
                                Paths.get(entry.target).normalize()
                        ) {
                            "Target de symlink divergiu: ${entry.path}"
                        }
                        symlinks++
                    }

                    RootfsEntryType.HARDLINK -> {
                        val targetRelative = resolveHardlinkTarget(entry, entryMap)
                            ?: error("Target de hardlink inválido: ${entry.path}")
                        val targetPath = root.resolve(targetRelative).normalize()

                        require(
                            Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
                                !Files.isSymbolicLink(path)
                        ) { "Hardlink ausente: ${entry.path}" }
                        require(Files.isSameFile(path, targetPath)) {
                            "Hardlink não compartilha inode com target: ${entry.path}"
                        }
                        hardlinks++
                    }

                    else -> Unit
                }
            }

            require(symlinks == expectedSymlinks) {
                "Contagem de symlinks divergiu."
            }
            require(hardlinks == expectedHardlinks) {
                "Contagem de hardlinks divergiu."
            }

            LinkPreparationResult(
                prepared = true,
                symlinks = symlinks,
                hardlinks = hardlinks,
                metadataSha256 = actual.sha256,
                message = "LINKS_VERIFY_OK",
            )
        }.getOrElse {
            failed("LINKS_VERIFY_FAILED: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun recoverInterruptedPreparation(
        runtime: InstalledRuntime,
        root: Path,
        links: List<RootfsMetadataEntry>,
    ): String? {
        val preparing = File(runtime.directory, PREPARING_MARKER_NAME)
        val finalMarker = File(runtime.directory, MARKER_NAME)
        if (finalMarker.exists()) {
            preparing.delete()
            return null
        }

        val shouldRecover = preparing.exists() || links.any { entry ->
            Files.exists(root.resolve(entry.path), LinkOption.NOFOLLOW_LINKS)
        }
        if (!shouldRecover) return null

        for (entry in links.asReversed()) {
            val path = root.resolve(entry.path).normalize()
            if (!path.startsWith(root)) {
                return "Recovery encontrou link fora do rootfs: ${entry.path}"
            }
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                return "Recovery recusou diretório inesperado em path de link: ${entry.path}"
            }
            runCatching { Files.deleteIfExists(path) }.getOrElse {
                return "Falha ao recuperar link interrompido ${entry.path}: ${it.message}"
            }
        }

        preparing.delete()
        return null
    }

    private fun validatePlan(
        root: Path,
        links: List<RootfsMetadataEntry>,
        entries: Map<String, RootfsMetadataEntry>,
    ): String? {
        for (entry in links) {
            val path = root.resolve(entry.path).normalize()
            if (!path.startsWith(root)) return "Link escapou do rootfs: ${entry.path}"
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return "Path de link já existe antes da materialização: ${entry.path}"
            }

            var parent = path.parent
            while (parent != null && parent != root) {
                if (Files.isSymbolicLink(parent)) {
                    return "Diretório pai é symlink: ${entry.path}"
                }
                parent = parent.parent
            }

            when (entry.type) {
                RootfsEntryType.SYMLINK -> {
                    if (GuestPath.resolveSymlinkTarget(entry.path, entry.target) == null) {
                        return "Target de symlink escapa do guest root: ${entry.path}"
                    }
                }

                RootfsEntryType.HARDLINK -> {
                    val target = resolveHardlinkTarget(entry, entries)
                        ?: return "Hardlink inválido: ${entry.path}"
                    val targetPath = root.resolve(target).normalize()
                    if (!targetPath.startsWith(root) || !SafeTreeOps.isPlainFile(targetPath)) {
                        return "Target físico de hardlink inválido: ${entry.path}"
                    }
                }

                else -> Unit
            }
        }
        return null
    }

    private fun resolveHardlinkTarget(
        start: RootfsMetadataEntry,
        entries: Map<String, RootfsMetadataEntry>,
    ): String? {
        val visited = HashSet<String>()
        var current = start

        repeat(40) {
            if (!visited.add(current.path)) return null
            val target = GuestPath.hardlinkTarget(current.target) ?: return null
            val targetEntry = entries[target] ?: return null
            when (targetEntry.type) {
                RootfsEntryType.FILE -> return target
                RootfsEntryType.HARDLINK -> current = targetEntry
                else -> return null
            }
        }
        return null
    }

    private fun markerValue(marker: File, key: String): String? =
        marker.useLines { lines ->
            lines.firstOrNull { it.startsWith("$key=") }
                ?.substringAfter('=')
        }

    private fun failed(message: String) = LinkPreparationResult(
        prepared = false,
        symlinks = 0,
        hardlinks = 0,
        metadataSha256 = null,
        message = message,
    )

    companion object {
        const val MARKER_NAME = "LINKS_PREPARED"
        const val PREPARING_MARKER_NAME = "LINKS_PREPARING"
        private const val MAX_METADATA_BYTES = 256L * 1024L * 1024L
    }
}
