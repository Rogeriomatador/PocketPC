package dev.pocketpc.core.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class InstalledGuestTool(
    val manifest: GuestToolManifest,
    val directory: File,
    val entrypoint: File,
)

class GuestToolInstallManager(
    private val installRoot: File,
) {
    init {
        require(installRoot.mkdirs() || installRoot.isDirectory) {
            "Não foi possível criar runtime-tools."
        }
    }

    suspend fun install(packageRoot: File): Result<InstalledGuestTool> =
        withContext(Dispatchers.IO) {
            runCatching {
                recoverInterruptedTransactions()

                val manifestFile = File(packageRoot, "guest-tool-manifest.json")
                require(SafeTreeOps.isPlainFile(manifestFile.toPath())) {
                    "GUEST_TOOL_MANIFEST_MISSING"
                }
                require(manifestFile.length() in 1..(16L * 1024L * 1024L)) {
                    "GUEST_TOOL_MANIFEST_SIZE_INVALID"
                }

                val manifest =
                    GuestToolManifestCodec.parse(
                        manifestFile.readText(),
                    )
                val sourceVerification =
                    GuestToolPackageVerifier.verify(
                        packageRoot = packageRoot,
                        manifest = manifest,
                    )
                require(sourceVerification.valid) {
                    "GUEST_TOOL_PACKAGE_INVALID:" +
                        sourceVerification.errors.joinToString(",")
                }

                val root = installRoot.canonicalFile
                val idDir = File(root, manifest.id).apply {
                    require(mkdirs() || isDirectory) {
                        "GUEST_TOOL_ID_DIRECTORY_FAILED"
                    }
                }
                val target = File(idDir, manifest.version)
                loadInstalled(target)?.let { existing ->
                    if (existing.manifest == manifest) {
                        return@runCatching existing
                    }
                }

                val transaction =
                    File(
                        idDir,
                        ".tmp-tool-" + manifest.version + "-" + System.nanoTime(),
                    ).apply {
                        require(mkdirs()) {
                            "GUEST_TOOL_TRANSACTION_CREATE_FAILED"
                        }
                    }

                try {
                    Files.copy(
                        manifestFile.toPath(),
                        File(transaction, "guest-tool-manifest.json").toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )

                    manifest.files.forEach { item ->
                        val source = File(packageRoot, item.path)
                        val destination = File(transaction, item.path)
                        require(destination.parentFile.mkdirs() || destination.parentFile.isDirectory) {
                            "GUEST_TOOL_PARENT_CREATE_FAILED:" + item.path
                        }
                        Files.copy(
                            source.toPath(),
                            destination.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                        if (item.executable) {
                            require(destination.setExecutable(true, true) || destination.canExecute()) {
                                "GUEST_TOOL_EXECUTABLE_PERMISSION_FAILED:" + item.path
                            }
                        }
                    }

                    val copiedVerification =
                        GuestToolPackageVerifier.verify(
                            packageRoot = transaction,
                            manifest = manifest,
                        )
                    require(copiedVerification.valid) {
                        "GUEST_TOOL_COPIED_PACKAGE_INVALID:" +
                            copiedVerification.errors.joinToString(",")
                    }

                    promote(transaction, target)
                    loadInstalled(target)
                        ?: error("GUEST_TOOL_PROMOTED_BUT_NOT_LOADABLE")
                } catch (error: Throwable) {
                    SafeTreeOps.deleteNoFollow(transaction)
                    throw error
                }
            }
        }

    suspend fun discover(): List<InstalledGuestTool> =
        withContext(Dispatchers.IO) {
            recoverInterruptedTransactions()
            installRoot.listFiles()
                .orEmpty()
                .filter { SafeTreeOps.isPlainDirectory(it.toPath()) && !it.name.startsWith(".") }
                .flatMap { idDir ->
                    idDir.listFiles()
                        .orEmpty()
                        .filter {
                            SafeTreeOps.isPlainDirectory(it.toPath()) &&
                                !it.name.startsWith(".")
                        }
                }
                .mapNotNull(::loadInstalled)
                .sortedWith(
                    compareBy<InstalledGuestTool> { it.manifest.id }
                        .thenBy { it.manifest.version },
                )
        }

    suspend fun remove(tool: InstalledGuestTool): Boolean =
        withContext(Dispatchers.IO) {
            val root = installRoot.canonicalFile
            val target =
                runCatching { tool.directory.canonicalFile }
                    .getOrNull()
                    ?: return@withContext false

            if (
                target == root ||
                !target.path.startsWith(root.path + File.separator)
            ) {
                return@withContext false
            }
            SafeTreeOps.deleteNoFollow(target)
        }

    private fun loadInstalled(directory: File): InstalledGuestTool? =
        runCatching {
            if (!SafeTreeOps.isPlainDirectory(directory.toPath())) {
                return@runCatching null
            }

            val manifestFile =
                File(directory, "guest-tool-manifest.json")
            if (
                !SafeTreeOps.isPlainFile(manifestFile.toPath()) ||
                manifestFile.length() !in 1..(16L * 1024L * 1024L)
            ) {
                return@runCatching null
            }

            val manifest =
                GuestToolManifestCodec.parse(
                    manifestFile.readText(),
                )
            if (GuestToolManifestValidator.errors(manifest).isNotEmpty()) {
                return@runCatching null
            }
            if (
                directory.name != manifest.version ||
                directory.parentFile?.name != manifest.id
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

            InstalledGuestTool(
                manifest = manifest,
                directory = directory,
                entrypoint = File(directory, manifest.entrypoint),
            )
        }.getOrNull()

    private fun promote(
        transaction: File,
        target: File,
    ) {
        val backup =
            File(
                target.parentFile,
                ".backup-tool-" + target.name + "-" + System.nanoTime(),
            )
        var oldMoved = false

        if (target.exists()) {
            require(target.renameTo(backup)) {
                "GUEST_TOOL_BACKUP_FAILED"
            }
            oldMoved = true
        }

        try {
            require(transaction.renameTo(target)) {
                "GUEST_TOOL_PROMOTION_FAILED"
            }
            if (oldMoved) {
                SafeTreeOps.deleteNoFollow(backup)
            }
        } catch (error: Throwable) {
            if (!target.exists() && oldMoved) {
                backup.renameTo(target)
            }
            throw error
        }
    }

    private fun recoverInterruptedTransactions() {
        installRoot.listFiles()
            .orEmpty()
            .filter { SafeTreeOps.isPlainDirectory(it.toPath()) }
            .forEach { idDir ->
                idDir.listFiles().orEmpty().forEach { child ->
                    when {
                        child.name.startsWith(".tmp-tool-") ->
                            SafeTreeOps.deleteNoFollow(child)
                        child.name.startsWith(".backup-tool-") -> {
                            val manifest =
                                runCatching {
                                    val file =
                                        File(
                                            child,
                                            "guest-tool-manifest.json",
                                        )
                                    GuestToolManifestCodec.parse(
                                        file.readText(),
                                    )
                                }.getOrNull()

                            if (
                                manifest == null ||
                                manifest.id != idDir.name
                            ) {
                                SafeTreeOps.deleteNoFollow(child)
                            } else {
                                val target =
                                    File(
                                        idDir,
                                        manifest.version,
                                    )
                                if (target.exists()) {
                                    SafeTreeOps.deleteNoFollow(child)
                                } else {
                                    child.renameTo(target)
                                }
                            }
                        }
                    }
                }
            }
    }
}
