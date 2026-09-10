package dev.pocketpc.core.runtime

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

data class StagedGuestToolPackage(
    val manifest: GuestToolManifest,
    val directory: File,
)

class GuestToolPackageManager(
    private val context: Context,
) {
    private val stagingRoot =
        File(
            context.noBackupFilesDir,
            "runtime-tools-staged",
        ).apply {
            require(mkdirs() || isDirectory) {
                "Não foi possível criar runtime-tools-staged."
            }
        }

    suspend fun stageZip(
        uriString: String,
    ): Result<StagedGuestToolPackage> =
        withContext(Dispatchers.IO) {
            runCatching {
                recoverInterruptedTransactions()

                val transaction =
                    File(
                        stagingRoot,
                        ".tmp-tool-stage-" + System.nanoTime(),
                    ).apply {
                        require(mkdirs()) {
                            "GUEST_TOOL_STAGE_TRANSACTION_FAILED"
                        }
                    }

                try {
                    extractZip(
                        uriString = uriString,
                        destination = transaction,
                    )

                    val manifestFile =
                        File(
                            transaction,
                            "guest-tool-manifest.json",
                        )
                    require(
                        SafeTreeOps.isPlainFile(
                            manifestFile.toPath(),
                        ),
                    ) {
                        "GUEST_TOOL_MANIFEST_MISSING"
                    }
                    require(
                        manifestFile.length() in
                            1..MAX_MANIFEST_BYTES,
                    ) {
                        "GUEST_TOOL_MANIFEST_SIZE_INVALID"
                    }

                    val manifest =
                        GuestToolManifestCodec.parse(
                            manifestFile.readText(),
                        )
                    val manifestErrors =
                        GuestToolManifestValidator.errors(
                            manifest,
                        )
                    require(manifestErrors.isEmpty()) {
                        manifestErrors.joinToString(",")
                    }

                    manifest.files
                        .filter { it.executable }
                        .forEach { item ->
                            val file =
                                File(
                                    transaction,
                                    item.path,
                                )
                            require(
                                file.setExecutable(
                                    true,
                                    true,
                                ) ||
                                    file.canExecute(),
                            ) {
                                "GUEST_TOOL_EXECUTABLE_PERMISSION_FAILED:" +
                                    item.path
                            }
                        }

                    val verification =
                        GuestToolPackageVerifier.verify(
                            packageRoot = transaction,
                            manifest = manifest,
                        )
                    require(verification.valid) {
                        "GUEST_TOOL_STAGE_ATTESTATION_FAILED:" +
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
                                "GUEST_TOOL_STAGE_ID_DIRECTORY_FAILED"
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

                    loadStaged(target)
                        ?: error(
                            "GUEST_TOOL_STAGE_PROMOTED_BUT_NOT_LOADABLE"
                        )
                } catch (error: Throwable) {
                    SafeTreeOps.deleteNoFollow(
                        transaction,
                    )
                    throw error
                }
            }
        }

    suspend fun discover(): List<StagedGuestToolPackage> =
        withContext(Dispatchers.IO) {
            recoverInterruptedTransactions()
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
                .mapNotNull(::loadStaged)
                .sortedWith(
                    compareBy<StagedGuestToolPackage> {
                        it.manifest.id
                    }.thenBy {
                        it.manifest.version
                    },
                )
        }

    suspend fun remove(
        staged: StagedGuestToolPackage,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val root = stagingRoot.canonicalFile
            val target =
                runCatching {
                    staged.directory.canonicalFile
                }.getOrNull()
                    ?: return@withContext false

            if (
                target == root ||
                !target.path.startsWith(
                    root.path + File.separator,
                )
            ) {
                return@withContext false
            }
            SafeTreeOps.deleteNoFollow(target)
        }

    private fun extractZip(
        uriString: String,
        destination: File,
    ) {
        val destinationRoot =
            destination.canonicalFile
        var entryCount = 0
        var totalBytes = 0L
        val seen = HashSet<String>()

        val input =
            context.contentResolver
                .openInputStream(
                    Uri.parse(uriString),
                )
                ?: error(
                    "Não foi possível abrir o pacote de guest tool."
                )

        input.buffered().use { raw ->
            ZipInputStream(raw).use { zip ->
                while (true) {
                    val entry =
                        zip.nextEntry
                            ?: break
                    entryCount += 1
                    require(
                        entryCount <= MAX_ENTRIES,
                    ) {
                        "GUEST_TOOL_ZIP_ENTRY_LIMIT_EXCEEDED"
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
                                "GUEST_TOOL_ZIP_PATH_INVALID:" +
                                    entry.name
                            )
                    require(seen.add(normalized)) {
                        "GUEST_TOOL_ZIP_DUPLICATE:" +
                            normalized
                    }

                    val target =
                        File(
                            destinationRoot,
                            normalized,
                        )
                    val canonicalTarget =
                        target.canonicalFile
                    require(
                        canonicalTarget.path.startsWith(
                            destinationRoot.path +
                                File.separator,
                        ),
                    ) {
                        "GUEST_TOOL_ZIP_PATH_ESCAPED:" +
                            normalized
                    }

                    if (entry.isDirectory) {
                        require(
                            target.mkdirs() ||
                                target.isDirectory,
                        ) {
                            "GUEST_TOOL_ZIP_DIRECTORY_FAILED:" +
                                normalized
                        }
                    } else {
                        val parent =
                            requireNotNull(
                                target.parentFile,
                            ) {
                                "GUEST_TOOL_ZIP_PARENT_MISSING:" +
                                    normalized
                            }
                        require(
                            parent.mkdirs() ||
                                parent.isDirectory,
                        ) {
                            "GUEST_TOOL_ZIP_PARENT_FAILED:" +
                                normalized
                        }

                        FileOutputStream(target).use {
                            output ->
                            val buffer =
                                ByteArray(
                                    DEFAULT_BUFFER_SIZE,
                                )
                            var entryBytes = 0L
                            while (true) {
                                val read =
                                    zip.read(buffer)
                                if (read < 0) {
                                    break
                                }
                                entryBytes += read
                                totalBytes += read
                                require(
                                    entryBytes <=
                                        MAX_SINGLE_FILE_BYTES,
                                ) {
                                    "GUEST_TOOL_ZIP_FILE_TOO_LARGE:" +
                                        normalized
                                }
                                require(
                                    totalBytes <=
                                        MAX_TOTAL_BYTES,
                                ) {
                                    "GUEST_TOOL_ZIP_TOTAL_TOO_LARGE"
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

        require(entryCount > 0) {
            "GUEST_TOOL_ZIP_EMPTY"
        }
    }

    private fun loadStaged(
        directory: File,
    ): StagedGuestToolPackage? =
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
                    "guest-tool-manifest.json",
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
                GuestToolManifestCodec.parse(
                    manifestFile.readText(),
                )
            if (
                GuestToolManifestValidator.errors(
                    manifest,
                ).isNotEmpty()
            ) {
                return@runCatching null
            }
            if (
                directory.name != manifest.version ||
                directory.parentFile?.name !=
                manifest.id
            ) {
                return@runCatching null
            }

            val verification =
                GuestToolPackageVerifier.verify(
                    packageRoot = directory,
                    manifest = manifest,
                )
            if (!verification.valid) {
                return@runCatching null
            }

            StagedGuestToolPackage(
                manifest = manifest,
                directory = directory,
            )
        }.getOrNull()

    private fun promote(
        transaction: File,
        target: File,
    ) {
        val backup =
            File(
                target.parentFile,
                ".backup-tool-stage-" +
                    target.name +
                    "-" +
                    System.nanoTime(),
            )
        var oldMoved = false

        if (target.exists()) {
            require(
                target.renameTo(backup),
            ) {
                "GUEST_TOOL_STAGE_BACKUP_FAILED"
            }
            oldMoved = true
        }

        try {
            require(
                transaction.renameTo(target),
            ) {
                "GUEST_TOOL_STAGE_PROMOTION_FAILED"
            }
            if (oldMoved) {
                SafeTreeOps.deleteNoFollow(
                    backup,
                )
            }
        } catch (error: Throwable) {
            if (
                !target.exists() &&
                oldMoved
            ) {
                backup.renameTo(target)
            }
            throw error
        }
    }

    private fun recoverInterruptedTransactions() {
        stagingRoot.listFiles()
            .orEmpty()
            .filter {
                SafeTreeOps.isPlainDirectory(
                    it.toPath(),
                )
            }
            .forEach { child ->
                if (
                    child.name.startsWith(
                        ".tmp-tool-stage-",
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
                        SafeTreeOps.isPlainDirectory(
                            it.toPath(),
                        ) &&
                            it.name.startsWith(
                                ".backup-tool-stage-",
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
        private const val MAX_ENTRIES =
            100_000
        private const val MAX_MANIFEST_BYTES =
            16L * 1024L * 1024L
        private const val MAX_SINGLE_FILE_BYTES =
            2L * 1024L * 1024L * 1024L
        private const val MAX_TOTAL_BYTES =
            8L * 1024L * 1024L * 1024L
    }
}
