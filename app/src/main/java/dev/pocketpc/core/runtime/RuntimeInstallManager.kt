package dev.pocketpc.core.runtime

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream

data class InstalledRuntime(
    val manifest: RuntimeManifest,
    val directory: File,
    val rootfsData: File,
    val metadataFile: File,
    val stats: ExtractionStats,
)

class RuntimeInstallManager(
    private val context: Context,
    private val packages: RuntimePackageManager,
) {
    private val installRoot =
        File(context.noBackupFilesDir, "runtimes/installed").apply { mkdirs() }

    suspend fun install(staged: StagedRuntime): Result<InstalledRuntime> =
        withContext(Dispatchers.IO) {
            runCatching {
                recoverInterruptedTransactions()
                require(RuntimeManifestValidator.canExtract(staged.manifest)) {
                    "Runtime precisa de manifesto schema v2 para extração."
                }

                val audit = packages.audit(staged)
                require(audit.valid) { "Staging não passou na auditoria SHA-256." }
                requireInstallSpace(staged.manifest)

                val idDir = File(installRoot, staged.manifest.id).apply {
                    require(mkdirs() || isDirectory) {
                        "Não foi possível criar diretório de instalação."
                    }
                }
                val targetDir = File(idDir, staged.manifest.version)
                val tempDir = File(idDir, ".tmp-install-${System.nanoTime()}").apply {
                    require(mkdirs()) { "Não foi possível criar transação de instalação." }
                }

                try {
                    val rootfsData = File(tempDir, "rootfs-data").apply {
                        require(mkdirs()) { "Não foi possível criar rootfs-data." }
                    }
                    val metadataFile = File(tempDir, "rootfs.metadata.tsv")

                    val stats = staged.archive.inputStream().buffered().use { raw ->
                        val payload = when (staged.manifest.archiveFormat) {
                            "tar" -> raw
                            "tar.gz" -> GZIPInputStream(raw, 64 * 1024)
                            else -> error("Formato de archive não suportado.")
                        }

                        payload.use {
                            SafeTarExtractor.extract(
                                input = it,
                                destination = rootfsData,
                                metadataFile = metadataFile,
                                policy = ExtractionPolicy(
                                    maxEntries = staged.manifest.entryLimit,
                                    maxExtractedBytes = staged.manifest.extractedBytesLimit,
                                ),
                            )
                        }
                    }

                    File(tempDir, "manifest.json").writeText(
                        RuntimeManifestCodec.encode(staged.manifest)
                    )
                    File(tempDir, "INSTALL_VERIFIED").writeText(
                        buildString {
                            appendLine("sourceSha256=${staged.manifest.rootfsSha256.lowercase()}")
                            appendLine("archiveBytes=${staged.manifest.rootfsBytes}")
                            appendLine("entries=${stats.entries}")
                            appendLine("regularFiles=${stats.regularFiles}")
                            appendLine("directories=${stats.directories}")
                            appendLine("linksRecorded=${stats.linksRecorded}")
                            appendLine("extractedBytes=${stats.extractedBytes}")
                            appendLine("linksMaterialized=false")
                        }
                    )

                    promoteInstalled(tempDir, targetDir)
                    loadInstalled(targetDir)
                        ?: error("Instalação promovida, mas metadados não puderam ser recarregados.")
                } catch (error: Throwable) {
                    tempDir.deleteRecursively()
                    throw error
                }
            }
        }

    suspend fun discover(): List<InstalledRuntime> = withContext(Dispatchers.IO) {
        recoverInterruptedTransactions()
        installRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .flatMap { id -> id.listFiles().orEmpty().filter(File::isDirectory) }
            .mapNotNull(::loadInstalled)
            .sortedWith(compareBy<InstalledRuntime> { it.manifest.name }.thenBy { it.manifest.version })
    }

    suspend fun remove(runtime: InstalledRuntime): Boolean = withContext(Dispatchers.IO) {
        val root = installRoot.canonicalFile
        val target = runtime.directory.canonicalFile
        if (!target.path.startsWith(root.path + File.separator)) return@withContext false
        target.deleteRecursively()
    }

    private fun loadInstalled(directory: File): InstalledRuntime? = runCatching {
        val manifestFile = File(directory, "manifest.json")
        val marker = File(directory, "INSTALL_VERIFIED")
        val rootfsData = File(directory, "rootfs-data")
        val metadata = File(directory, "rootfs.metadata.tsv")

        if (
            !manifestFile.isFile ||
            !marker.isFile ||
            !rootfsData.isDirectory ||
            !metadata.isFile
        ) {
            return@runCatching null
        }

        val manifest = RuntimeManifestCodec.parse(manifestFile.readText())
        if (!RuntimeManifestValidator.canExtract(manifest)) return@runCatching null
        if (directory.name != manifest.version || directory.parentFile?.name != manifest.id) {
            return@runCatching null
        }

        val values = marker.readLines()
            .mapNotNull { line ->
                val split = line.indexOf('=')
                if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
            }
            .toMap()

        if (!values["sourceSha256"].equals(manifest.rootfsSha256, ignoreCase = true)) {
            return@runCatching null
        }
        if (values["linksMaterialized"] != "false") return@runCatching null

        val stats = ExtractionStats(
            entries = values["entries"]?.toIntOrNull() ?: return@runCatching null,
            regularFiles = values["regularFiles"]?.toIntOrNull() ?: return@runCatching null,
            directories = values["directories"]?.toIntOrNull() ?: return@runCatching null,
            linksRecorded = values["linksRecorded"]?.toIntOrNull() ?: return@runCatching null,
            extractedBytes = values["extractedBytes"]?.toLongOrNull() ?: return@runCatching null,
        )

        InstalledRuntime(manifest, directory, rootfsData, metadata, stats)
    }.getOrNull()

    private fun requireInstallSpace(manifest: RuntimeManifest) {
        val available = StatFs(installRoot.absolutePath).availableBytes
        val reserve = 256L * 1024L * 1024L
        val required = Math.addExact(manifest.extractedBytesLimit, reserve)
        require(available >= required) {
            "Espaço insuficiente para o limite de extração: necessário=${required}B, livre=${available}B."
        }
    }

    private fun promoteInstalled(tempDir: File, targetDir: File) {
        val backup = File(
            targetDir.parentFile,
            ".backup-install-${targetDir.name}-${System.nanoTime()}",
        )
        var oldMoved = false

        if (targetDir.exists()) {
            require(targetDir.renameTo(backup)) {
                "Não foi possível preservar instalação anterior."
            }
            oldMoved = true
        }

        try {
            require(tempDir.renameTo(targetDir)) {
                "Não foi possível promover instalação."
            }
            if (oldMoved) backup.deleteRecursively()
        } catch (error: Throwable) {
            if (!targetDir.exists() && oldMoved) backup.renameTo(targetDir)
            throw error
        }
    }

    private fun recoverInterruptedTransactions() {
        for (idDir in installRoot.listFiles().orEmpty().filter(File::isDirectory)) {
            for (child in idDir.listFiles().orEmpty()) {
                if (child.isDirectory && child.name.startsWith(".tmp-install-")) {
                    child.deleteRecursively()
                }
            }

            for (backup in idDir.listFiles().orEmpty()) {
                if (!backup.isDirectory || !backup.name.startsWith(".backup-install-")) continue
                val manifest = runCatching {
                    RuntimeManifestCodec.parse(File(backup, "manifest.json").readText())
                }.getOrNull()

                if (manifest == null || manifest.id != idDir.name) {
                    backup.deleteRecursively()
                    continue
                }
                val target = File(idDir, manifest.version)
                if (target.exists()) {
                    backup.deleteRecursively()
                } else {
                    backup.renameTo(target)
                }
            }
        }
    }
}
