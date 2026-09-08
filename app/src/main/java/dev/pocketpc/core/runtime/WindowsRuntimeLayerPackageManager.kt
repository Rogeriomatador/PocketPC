package dev.pocketpc.core.runtime

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

data class StagedWindowsRuntimeLayer(
    val manifest: WindowsRuntimeLayerManifest,
    val directory: File,
)

class WindowsRuntimeLayerPackageManager(
    private val context: Context,
) {
    private val stagingRoot =
        File(
            context.noBackupFilesDir,
            "windows-runtime-layers/staged",
        ).apply {
            require(mkdirs() || isDirectory) {
                "WINDOWS_LAYER_STAGING_ROOT_FAILED"
            }
        }

    suspend fun stageZip(
        uriString: String,
    ): Result<StagedWindowsRuntimeLayer> =
        withContext(Dispatchers.IO) {
            runCatching {
                recoverTransactions()
                val transaction =
                    File(
                        stagingRoot,
                        ".tmp-layer-" +
                            System.nanoTime(),
                    ).apply {
                        require(mkdirs()) {
                            "WINDOWS_LAYER_TRANSACTION_FAILED"
                        }
                    }

                try {
                    extractZip(
                        uriString,
                        transaction,
                    )
                    val manifestFile =
                        File(
                            transaction,
                            MANIFEST_NAME,
                        )
                    require(
                        SafeTreeOps.isPlainFile(
                            manifestFile.toPath(),
                        ),
                    ) {
                        "WINDOWS_LAYER_MANIFEST_MISSING"
                    }
                    require(
                        manifestFile.length() in
                            1..MAX_MANIFEST_BYTES
                    ) {
                        "WINDOWS_LAYER_MANIFEST_SIZE_INVALID"
                    }

                    val manifest =
                        WindowsRuntimeLayerManifestCodec
                            .parse(
                                manifestFile.readText(),
                            )
                    val verification =
                        WindowsRuntimeLayerPackageVerifier
                            .verify(
                                transaction,
                                manifest,
                            )
                    require(verification.valid) {
                        "WINDOWS_LAYER_ATTESTATION_FAILED:" +
                            verification.errors
                                .joinToString(",")
                    }

                    val idDir =
                        File(
                            stagingRoot,
                            manifest.id,
                        ).apply {
                            require(
                                mkdirs() ||
                                    isDirectory,
                            ) {
                                "WINDOWS_LAYER_ID_DIRECTORY_FAILED"
                            }
                        }
                    val target =
                        File(
                            idDir,
                            manifest.version,
                        )
                    promote(
                        transaction,
                        target,
                    )
                    load(target)
                        ?: error(
                            "WINDOWS_LAYER_PROMOTED_BUT_NOT_LOADABLE"
                        )
                } catch (error: Throwable) {
                    SafeTreeOps.deleteNoFollow(
                        transaction,
                    )
                    throw error
                }
            }
        }

    suspend fun discover():
        List<StagedWindowsRuntimeLayer> =
        withContext(Dispatchers.IO) {
            recoverTransactions()
            stagingRoot.listFiles()
                .orEmpty()
                .filter {
                    SafeTreeOps.isPlainDirectory(
                        it.toPath(),
                    ) &&
                        !it.name.startsWith(".")
                }
                .flatMap { idDir ->
                    idDir.listFiles()
                        .orEmpty()
                        .filter {
                            SafeTreeOps.isPlainDirectory(
                                it.toPath(),
                            ) &&
                                !it.name.startsWith(".")
                        }
                }
                .mapNotNull(::load)
                .sortedWith(
                    compareBy<StagedWindowsRuntimeLayer> {
                        it.manifest.id
                    }.thenBy {
                        it.manifest.version
                    },
                )
        }

    suspend fun remove(
        layer: StagedWindowsRuntimeLayer,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val root =
                stagingRoot.canonicalFile
            val target =
                runCatching {
                    layer.directory.canonicalFile
                }.getOrNull()
                    ?: return@withContext false

            if (
                target == root ||
                !target.path.startsWith(
                    root.path +
                        File.separator,
                )
            ) {
                return@withContext false
            }
            SafeTreeOps.deleteNoFollow(
                target,
            )
        }

    private fun load(
        directory: File,
    ): StagedWindowsRuntimeLayer? =
        runCatching {
            if (
                !SafeTreeOps.isPlainDirectory(
                    directory.toPath(),
                )
            ) {
                return@runCatching null
            }

            val manifestFile =
                File(
                    directory,
                    MANIFEST_NAME,
                )
            if (
                !SafeTreeOps.isPlainFile(
                    manifestFile.toPath(),
                ) ||
                manifestFile.length() !in
                1..MAX_MANIFEST_BYTES
            ) {
                return@runCatching null
            }

            val manifest =
                WindowsRuntimeLayerManifestCodec
                    .parse(
                        manifestFile.readText(),
                    )
            if (
                directory.name !=
                    manifest.version ||
                directory.parentFile?.name !=
                    manifest.id
            ) {
                return@runCatching null
            }

            val verification =
                WindowsRuntimeLayerPackageVerifier
                    .verify(
                        directory,
                        manifest,
                    )
            if (!verification.valid) {
                return@runCatching null
            }

            StagedWindowsRuntimeLayer(
                manifest,
                directory,
            )
        }.getOrNull()

    private fun extractZip(
        uriString: String,
        destination: File,
    ) {
        val root =
            destination.canonicalFile
        var entries = 0
        var totalBytes = 0L
        val seen = HashSet<String>()

        val input =
            context.contentResolver
                .openInputStream(
                    Uri.parse(uriString),
                )
                ?: error(
                    "WINDOWS_LAYER_ZIP_OPEN_FAILED"
                )

        input.buffered().use { raw ->
            ZipInputStream(raw).use { zip ->
                while (true) {
                    val entry =
                        zip.nextEntry
                            ?: break
                    entries += 1
                    require(
                        entries <= MAX_ENTRIES,
                    ) {
                        "WINDOWS_LAYER_ZIP_ENTRY_LIMIT"
                    }

                    val rawName =
                        if (entry.isDirectory) {
                            entry.name.trimEnd('/')
                        } else {
                            entry.name
                        }
                    val normalized =
                        GuestToolManifestValidator
                            .normalizeRelative(
                                rawName,
                            )
                            ?: error(
                                "WINDOWS_LAYER_ZIP_PATH_INVALID:" +
                                    entry.name
                            )
                    require(
                        seen.add(normalized),
                    ) {
                        "WINDOWS_LAYER_ZIP_DUPLICATE:" +
                            normalized
                    }

                    val target =
                        File(root, normalized)
                    val canonical =
                        target.canonicalFile
                    require(
                        canonical.path.startsWith(
                            root.path +
                                File.separator,
                        ),
                    ) {
                        "WINDOWS_LAYER_ZIP_ESCAPE:" +
                            normalized
                    }

                    if (entry.isDirectory) {
                        require(
                            target.mkdirs() ||
                                target.isDirectory,
                        ) {
                            "WINDOWS_LAYER_ZIP_DIRECTORY_FAILED"
                        }
                    } else {
                        require(
                            target.parentFile.mkdirs() ||
                                target.parentFile.isDirectory,
                        ) {
                            "WINDOWS_LAYER_ZIP_PARENT_FAILED"
                        }

                        FileOutputStream(target)
                            .use { output ->
                                val buffer =
                                    ByteArray(
                                        DEFAULT_BUFFER_SIZE,
                                    )
                                var fileBytes = 0L
                                while (true) {
                                    val read =
                                        zip.read(buffer)
                                    if (read < 0) {
                                        break
                                    }
                                    fileBytes += read
                                    totalBytes += read
                                    require(
                                        fileBytes <=
                                            MAX_FILE_BYTES,
                                    ) {
                                        "WINDOWS_LAYER_ZIP_FILE_LIMIT"
                                    }
                                    require(
                                        totalBytes <=
                                            MAX_TOTAL_BYTES,
                                    ) {
                                        "WINDOWS_LAYER_ZIP_TOTAL_LIMIT"
                                    }
                                    output.write(
                                        buffer,
                                        0,
                                        read,
                                    )
                                }
                                output.fd.sync()
                            }
                    }
                    zip.closeEntry()
                }
            }
        }

        require(entries > 0) {
            "WINDOWS_LAYER_ZIP_EMPTY"
        }
    }

    private fun promote(
        transaction: File,
        target: File,
    ) {
        val backup =
            File(
                target.parentFile,
                ".backup-layer-" +
                    target.name +
                    "-" +
                    System.nanoTime(),
            )
        var moved = false

        if (target.exists()) {
            require(target.renameTo(backup)) {
                "WINDOWS_LAYER_BACKUP_FAILED"
            }
            moved = true
        }

        try {
            require(
                transaction.renameTo(target),
            ) {
                "WINDOWS_LAYER_PROMOTION_FAILED"
            }
            if (moved) {
                SafeTreeOps.deleteNoFollow(
                    backup,
                )
            }
        } catch (error: Throwable) {
            if (
                !target.exists() &&
                moved
            ) {
                backup.renameTo(target)
            }
            throw error
        }
    }

    private fun recoverTransactions() {
        stagingRoot.listFiles()
            .orEmpty()
            .forEach { child ->
                if (
                    child.name.startsWith(
                        ".tmp-layer-",
                    )
                ) {
                    SafeTreeOps.deleteNoFollow(
                        child,
                    )
                }
            }

        stagingRoot.listFiles()
            .orEmpty()
            .filter {
                SafeTreeOps.isPlainDirectory(
                    it.toPath(),
                ) &&
                    !it.name.startsWith(".")
            }
            .forEach { idDir ->
                idDir.listFiles()
                    .orEmpty()
                    .filter {
                        it.name.startsWith(
                            ".backup-layer-",
                        )
                    }
                    .forEach {
                        SafeTreeOps.deleteNoFollow(
                            it,
                        )
                    }
            }
    }

    companion object {
        private const val MANIFEST_NAME =
            "windows-layer-manifest.json"
        private const val MAX_ENTRIES = 128
        private const val MAX_MANIFEST_BYTES =
            2L * 1024L * 1024L
        private const val MAX_FILE_BYTES =
            1024L * 1024L * 1024L
        private const val MAX_TOTAL_BYTES =
            2L * 1024L * 1024L * 1024L
    }
}
