package dev.pocketpc.core.runtime

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

data class StagedRuntime(
    val manifest: RuntimeManifest,
    val directory: File,
    val archive: File,
    val stagedBytes: Long,
)

data class RuntimeAudit(
    val valid: Boolean,
    val actualSha256: String?,
    val actualBytes: Long?,
    val message: String,
)

class RuntimePackageManager(private val context: Context) {
    private val runtimeRoot = File(context.noBackupFilesDir, "runtimes/staged").apply { mkdirs() }

    suspend fun readManifest(uriString: String): Result<RuntimeManifest> = withContext(Dispatchers.IO) {
        runCatching { readManifestBlocking(uriString) }
    }

    suspend fun stage(
        manifestUriString: String,
        rootfsUriString: String,
    ): Result<StagedRuntime> = withContext(Dispatchers.IO) {
        runCatching {
            recoverInterruptedTransactions()

            val manifest = readManifestBlocking(manifestUriString)
            val validation = RuntimeManifestValidator.validate(
                manifest = manifest,
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
            )
            require(validation.valid) { validation.errors.joinToString(" ") }
            requireEnoughSpace(manifest.rootfsBytes)

            val idDir = File(runtimeRoot, manifest.id).apply { mkdirs() }
            val targetDir = File(idDir, manifest.version)
            val tempDir = File(idDir, ".tmp-${System.nanoTime()}").apply {
                require(mkdirs()) { "Não foi possível criar staging temporário." }
            }

            try {
                val archive = File(tempDir, "rootfs.archive")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L

                context.contentResolver.openInputStream(Uri.parse(rootfsUriString))?.use { input ->
                    FileOutputStream(archive).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= manifest.rootfsBytes) {
                                "Rootfs é maior que rootfsBytes declarado."
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                } ?: error("Não foi possível abrir o arquivo rootfs.")

                require(total == manifest.rootfsBytes) {
                    "Tamanho do rootfs difere do manifesto: esperado=${manifest.rootfsBytes}, lido=$total."
                }

                val hash = digest.digest().toHex()
                require(hash.equals(manifest.rootfsSha256, ignoreCase = true)) {
                    "SHA-256 do rootfs não confere."
                }

                File(tempDir, "manifest.json").writeText(RuntimeManifestCodec.encode(manifest))
                File(tempDir, "VERIFIED").writeText("sha256=$hash\nbytes=$total\n")

                promoteVerified(tempDir, targetDir)
                val staged =
                    loadStagedRuntime(targetDir)
                        ?: error("RUNTIME_STAGED_PROMOTED_BUT_NOT_LOADABLE")
                val cleanup =
                    VersionedInstallPruner.prune(
                        containerRoot = runtimeRoot,
                        componentId = staged.manifest.id,
                        keepVersion = staged.manifest.version,
                    )
                require(cleanup.failedVersions.isEmpty()) {
                    "RUNTIME_STAGED_SUPERSEDED_CLEANUP_FAILED:" +
                        cleanup.failedVersions.joinToString(",")
                }
                staged
            } catch (error: Throwable) {
                tempDir.deleteRecursively()
                throw error
            }
        }
    }

    suspend fun discover(): List<StagedRuntime> = withContext(Dispatchers.IO) {
        recoverInterruptedTransactions()

        runtimeRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .flatMap { idDir ->
                idDir.listFiles().orEmpty()
                    .filter { it.isDirectory && !it.name.startsWith(".") }
            }
            .mapNotNull { directory ->
                loadStagedRuntime(directory)
            }
            .sortedWith(compareBy<StagedRuntime> { it.manifest.name }.thenBy { it.manifest.version })
    }

    suspend fun audit(runtime: StagedRuntime): RuntimeAudit = withContext(Dispatchers.IO) {
        runCatching {
            val canonicalRoot = runtimeRoot.canonicalFile
            val canonicalArchive = runtime.archive.canonicalFile
            require(canonicalArchive.path.startsWith(canonicalRoot.path + File.separator)) {
                "Archive fora do diretório de runtimes."
            }
            require(canonicalArchive.isFile) { "Archive do rootfs não existe." }

            val digest = canonicalArchive.inputStream().buffered().use { input ->
                Sha256.digest(
                    input = input,
                    maxBytes = runtime.manifest.rootfsBytes,
                )
            }
            val sizeOk = digest.bytes == runtime.manifest.rootfsBytes
            val hashOk = digest.sha256.equals(runtime.manifest.rootfsSha256, ignoreCase = true)
            RuntimeAudit(
                valid = sizeOk && hashOk,
                actualSha256 = digest.sha256,
                actualBytes = digest.bytes,
                message = if (sizeOk && hashOk) "AUDIT_OK" else "Hash/tamanho divergente do manifesto.",
            )
        }.getOrElse {
            RuntimeAudit(
                valid = false,
                actualSha256 = null,
                actualBytes = null,
                message = "AUDIT_FAILED: ${it.message ?: it.javaClass.simpleName}",
            )
        }
    }

    suspend fun remove(runtime: StagedRuntime): Boolean = withContext(Dispatchers.IO) {
        val canonicalRoot = runtimeRoot.canonicalFile
        val canonicalTarget = runtime.directory.canonicalFile
        if (!canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) return@withContext false
        canonicalTarget.deleteRecursively()
    }

    private fun loadStagedRuntime(directory: File): StagedRuntime? = runCatching {
        val manifestFile = File(directory, "manifest.json")
        val archive = File(directory, "rootfs.archive")
        val verified = File(directory, "VERIFIED")
        if (!manifestFile.isFile || !archive.isFile || !verified.isFile) return@runCatching null

        val manifest = RuntimeManifestCodec.parse(manifestFile.readText())
        val validation = RuntimeManifestValidator.validate(manifest, Build.SUPPORTED_ABIS.toList())
        if (!validation.valid) return@runCatching null
        if (directory.name != manifest.version || directory.parentFile?.name != manifest.id) return@runCatching null

        StagedRuntime(manifest, directory, archive, archive.length())
    }.getOrNull()

    private fun readManifestBlocking(uriString: String): RuntimeManifest {
        val text = context.contentResolver.openInputStream(Uri.parse(uriString))?.use {
            readUtf8Limited(it, MAX_MANIFEST_BYTES)
        } ?: error("Não foi possível abrir o manifesto.")
        return RuntimeManifestCodec.parse(text)
    }

    private fun requireEnoughSpace(rootfsBytes: Long) {
        val available = StatFs(runtimeRoot.absolutePath).availableBytes
        val reserve = minOf(MIN_FREE_RESERVE_BYTES, maxOf(32L * 1024L * 1024L, rootfsBytes / 20L))
        require(available >= rootfsBytes + reserve) {
            "Espaço insuficiente: rootfs=${rootfsBytes}B, livre=${available}B, reserva=${reserve}B."
        }
    }

    private fun promoteVerified(tempDir: File, targetDir: File) {
        val backupDir = File(targetDir.parentFile, ".backup-${targetDir.name}-${System.nanoTime()}")
        var oldMoved = false

        if (targetDir.exists()) {
            require(targetDir.renameTo(backupDir)) { "Não foi possível preservar staging anterior." }
            oldMoved = true
        }

        try {
            require(tempDir.renameTo(targetDir)) { "Falha ao promover staging verificado." }
            if (oldMoved) backupDir.deleteRecursively()
        } catch (error: Throwable) {
            if (!targetDir.exists() && oldMoved) backupDir.renameTo(targetDir)
            throw error
        }
    }

    private fun recoverInterruptedTransactions() {
        for (idDir in runtimeRoot.listFiles().orEmpty().filter(File::isDirectory)) {
            for (temp in idDir.listFiles().orEmpty()) {
                if (temp.isDirectory && temp.name.startsWith(".tmp-")) {
                    temp.deleteRecursively()
                }
            }

            for (backup in idDir.listFiles().orEmpty()) {
                if (!backup.isDirectory || !backup.name.startsWith(".backup-")) continue

                val manifest = runCatching {
                    RuntimeManifestCodec.parse(File(backup, "manifest.json").readText())
                }.getOrNull()

                if (manifest == null || manifest.id != idDir.name) {
                    backup.deleteRecursively()
                    continue
                }

                val validation = RuntimeManifestValidator.validate(
                    manifest,
                    Build.SUPPORTED_ABIS.toList(),
                )
                if (!validation.valid) {
                    backup.deleteRecursively()
                    continue
                }

                val target = File(idDir, manifest.version)
                if (target.exists()) {
                    backup.deleteRecursively()
                } else if (!backup.renameTo(target)) {
                    // Keep the backup intact so a later recovery pass can retry.
                }
            }
        }
    }

    companion object {
        private const val MAX_MANIFEST_BYTES = 128 * 1024
        private const val MIN_FREE_RESERVE_BYTES = 256L * 1024L * 1024L

        internal fun readUtf8Limited(input: InputStream, maxBytes: Int): String {
            require(maxBytes > 0)
            val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
            val buffer = ByteArray(4096)
            var total = 0

            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= maxBytes) { "Manifesto excede 128 KiB." }
                output.write(buffer, 0, read)
            }
            return output.toByteArray().toString(Charsets.UTF_8)
        }
    }
}
