package dev.pocketpc.core.runtime

import android.content.Context
import android.net.Uri
import android.os.Build
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
            val manifest = readManifestBlocking(manifestUriString)
            val validation = RuntimeManifestValidator.validate(
                manifest = manifest,
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
            )
            require(validation.valid) { validation.errors.joinToString(" ") }

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

                StagedRuntime(
                    manifest = manifest,
                    directory = targetDir,
                    archive = File(targetDir, "rootfs.archive"),
                    stagedBytes = total,
                )
            } catch (error: Throwable) {
                tempDir.deleteRecursively()
                throw error
            }
        }
    }

    suspend fun discover(): List<StagedRuntime> = withContext(Dispatchers.IO) {
        runtimeRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .flatMap { idDir -> idDir.listFiles().orEmpty().filter(File::isDirectory) }
            .mapNotNull { directory ->
                runCatching {
                    val manifestFile = File(directory, "manifest.json")
                    val archive = File(directory, "rootfs.archive")
                    val verified = File(directory, "VERIFIED")
                    if (!manifestFile.isFile || !archive.isFile || !verified.isFile) return@runCatching null
                    val manifest = RuntimeManifestCodec.parse(manifestFile.readText())
                    StagedRuntime(manifest, directory, archive, archive.length())
                }.getOrNull()
            }
            .sortedWith(compareBy<StagedRuntime> { it.manifest.name }.thenBy { it.manifest.version })
    }

    suspend fun remove(runtime: StagedRuntime): Boolean = withContext(Dispatchers.IO) {
        val canonicalRoot = runtimeRoot.canonicalFile
        val canonicalTarget = runtime.directory.canonicalFile
        if (!canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) return@withContext false
        canonicalTarget.deleteRecursively()
    }

    private fun readManifestBlocking(uriString: String): RuntimeManifest {
        val text = context.contentResolver.openInputStream(Uri.parse(uriString))?.use {
            readUtf8Limited(it, MAX_MANIFEST_BYTES)
        } ?: error("Não foi possível abrir o manifesto.")
        return RuntimeManifestCodec.parse(text)
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
            if (!targetDir.exists() && oldMoved) {
                backupDir.renameTo(targetDir)
            }
            throw error
        }
    }

    companion object {
        private const val MAX_MANIFEST_BYTES = 128 * 1024

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
