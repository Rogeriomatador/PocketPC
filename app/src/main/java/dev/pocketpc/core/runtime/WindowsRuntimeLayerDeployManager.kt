package dev.pocketpc.core.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class DeployedWindowsRuntimeLayer(
    val manifest: WindowsRuntimeLayerManifest,
    val deploymentDirectory: File,
)

class WindowsRuntimeLayerDeployManager(
    private val stateRoot: File,
) {
    init {
        require(
            stateRoot.mkdirs() ||
                stateRoot.isDirectory,
        ) {
            "WINDOWS_LAYER_STATE_ROOT_FAILED"
        }
    }

    suspend fun deploy(
        layer: StagedWindowsRuntimeLayer,
        prefixPlan: WindowsPrefixPlan,
    ): Result<DeployedWindowsRuntimeLayer> =
        withContext(Dispatchers.IO) {
            runCatching {
                val prefix =
                    prefixPlan.layout
                        ?: error(
                            "WINDOWS_LAYER_PREFIX_NOT_READY"
                        )
                require(
                    WindowsPrefixReadinessProbe
                        .assess(prefixPlan)
                        .ready,
                ) {
                    "WINDOWS_LAYER_PREFIX_NOT_READY"
                }

                val packageVerification =
                    WindowsRuntimeLayerPackageVerifier
                        .verify(
                            layer.directory,
                            layer.manifest,
                        )
                require(
                    packageVerification.valid,
                ) {
                    "WINDOWS_LAYER_PACKAGE_INVALID:" +
                        packageVerification.errors
                            .joinToString(",")
                }

                val system32 =
                    File(
                        prefix.prefixRoot,
                        layer.manifest
                            .targetDirectory,
                    ).canonicalFile
                val expectedSystem32 =
                    File(
                        prefix.prefixRoot,
                        "drive_c/windows/system32",
                    ).canonicalFile

                require(
                    system32 == expectedSystem32 &&
                        SafeTreeOps.isPlainDirectory(
                            system32.toPath(),
                        ),
                ) {
                    "WINDOWS_LAYER_SYSTEM32_INVALID"
                }

                val layerState =
                    File(
                        stateRoot,
                        layer.manifest.id +
                            "/" +
                            layer.manifest.version,
                    )

                if (layerState.exists()) {
                    val existing =
                        loadDeployment(
                            layerState,
                            system32,
                        )
                    require(existing != null) {
                        "WINDOWS_LAYER_EXISTING_DEPLOYMENT_INVALID"
                    }
                    return@runCatching existing
                }

                val transaction =
                    File(
                        layerState.parentFile,
                        ".tmp-deploy-" +
                            layer.manifest.version +
                            "-" +
                            System.nanoTime(),
                    ).apply {
                        require(
                            mkdirs(),
                        ) {
                            "WINDOWS_LAYER_DEPLOY_TRANSACTION_FAILED"
                        }
                    }
                val backups =
                    File(
                        transaction,
                        "backups",
                    ).apply {
                        require(
                            mkdirs(),
                        )
                    }

                val replaced =
                    mutableListOf<
                        Pair<File, File?>
                    >()

                try {
                    layer.manifest.files
                        .forEach { item ->
                            val source =
                                File(
                                    layer.directory,
                                    item.path,
                                )
                            val destination =
                                File(
                                    system32,
                                    item.destinationName,
                                )

                            require(
                                destination.parentFile
                                    .canonicalFile ==
                                    system32,
                            ) {
                                "WINDOWS_LAYER_DESTINATION_ESCAPED"
                            }

                            val backup =
                                if (
                                    destination.exists()
                                ) {
                                    require(
                                        SafeTreeOps
                                            .isPlainFile(
                                                destination
                                                    .toPath(),
                                            ),
                                    ) {
                                        "WINDOWS_LAYER_EXISTING_DLL_NOT_PLAIN:" +
                                            item.destinationName
                                    }
                                    File(
                                        backups,
                                        item.destinationName,
                                    ).also {
                                        Files.copy(
                                            destination.toPath(),
                                            it.toPath(),
                                            StandardCopyOption
                                                .REPLACE_EXISTING,
                                        )
                                    }
                                } else {
                                    null
                                }

                            val temp =
                                File(
                                    system32,
                                    "." +
                                        item.destinationName +
                                        ".pocketpc-" +
                                        System.nanoTime(),
                                )
                            Files.copy(
                                source.toPath(),
                                temp.toPath(),
                                StandardCopyOption
                                    .REPLACE_EXISTING,
                            )

                            val digest =
                                temp.inputStream()
                                    .buffered()
                                    .use {
                                        Sha256.digest(
                                            input = it,
                                            maxBytes =
                                                maxOf(
                                                    1L,
                                                    item.bytes,
                                                ),
                                        )
                                    }
                            require(
                                digest.bytes ==
                                    item.bytes &&
                                    digest.sha256 ==
                                    item.sha256,
                            ) {
                                "WINDOWS_LAYER_DEPLOY_COPY_ATTESTATION_FAILED:" +
                                    item.destinationName
                            }

                            Files.move(
                                temp.toPath(),
                                destination.toPath(),
                                StandardCopyOption
                                    .REPLACE_EXISTING,
                            )
                            replaced +=
                                destination to
                                    backup
                        }

                    Files.copy(
                        File(
                            layer.directory,
                            "windows-layer-manifest.json",
                        ).toPath(),
                        File(
                            transaction,
                            "windows-layer-manifest.json",
                        ).toPath(),
                        StandardCopyOption
                            .REPLACE_EXISTING,
                    )

                    val deploymentManifest =
                        File(
                            transaction,
                            "DEPLOYMENT.tsv",
                        )
                    deploymentManifest.writeText(
                        buildString {
                            appendLine(
                                "id=" +
                                    layer.manifest.id,
                            )
                            appendLine(
                                "version=" +
                                    layer.manifest.version,
                            )
                            appendLine(
                                "sourceCommit=" +
                                    layer.manifest
                                        .sourceCommit,
                            )
                            layer.manifest.files
                                .forEach {
                                    append(
                                        "file=",
                                    )
                                    append(
                                        it.destinationName,
                                    )
                                    append('|')
                                    append(
                                        it.sha256,
                                    )
                                    append('|')
                                    appendLine(
                                        if (
                                            File(
                                                backups,
                                                it.destinationName,
                                            ).isFile
                                        ) {
                                            "backup"
                                        } else {
                                            "new"
                                        },
                                    )
                                }
                        },
                    )

                    require(
                        layerState.parentFile
                            .mkdirs() ||
                            layerState.parentFile
                                .isDirectory,
                    )
                    require(
                        transaction.renameTo(
                            layerState,
                        ),
                    ) {
                        "WINDOWS_LAYER_DEPLOY_STATE_PROMOTION_FAILED"
                    }

                    verifyDeployment(
                        layer.manifest,
                        system32,
                    )

                    DeployedWindowsRuntimeLayer(
                        manifest =
                            layer.manifest,
                        deploymentDirectory =
                            layerState,
                    )
                } catch (error: Throwable) {
                    replaced.asReversed()
                        .forEach {
                            (destination, backup) ->
                            if (
                                backup != null &&
                                backup.isFile
                            ) {
                                Files.copy(
                                    backup.toPath(),
                                    destination.toPath(),
                                    StandardCopyOption
                                        .REPLACE_EXISTING,
                                )
                            } else {
                                Files.deleteIfExists(
                                    destination.toPath(),
                                )
                            }
                        }
                    SafeTreeOps.deleteNoFollow(
                        transaction,
                    )
                    throw error
                }
            }
        }

    suspend fun remove(
        deployed: DeployedWindowsRuntimeLayer,
        prefixPlan: WindowsPrefixPlan,
    ): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                val prefix =
                    prefixPlan.layout
                        ?: error(
                            "WINDOWS_LAYER_PREFIX_NOT_READY"
                        )
                val system32 =
                    File(
                        prefix.prefixRoot,
                        "drive_c/windows/system32",
                    ).canonicalFile
                val backups =
                    File(
                        deployed
                            .deploymentDirectory,
                        "backups",
                    )

                deployed.manifest.files
                    .forEach { item ->
                        val destination =
                            File(
                                system32,
                                item.destinationName,
                            )
                        require(
                            SafeTreeOps.isPlainFile(
                                destination.toPath(),
                            ),
                        ) {
                            "WINDOWS_LAYER_REMOVE_CURRENT_DLL_MISSING:" +
                                item.destinationName
                        }

                        val current =
                            destination
                                .inputStream()
                                .buffered()
                                .use {
                                    Sha256.digest(
                                        it,
                                        maxOf(
                                            1L,
                                            item.bytes,
                                        ),
                                    )
                                }
                        require(
                            current.sha256 ==
                                item.sha256 &&
                                current.bytes ==
                                item.bytes,
                        ) {
                            "WINDOWS_LAYER_REMOVE_REFUSED_DLL_CHANGED:" +
                                item.destinationName
                        }
                    }

                deployed.manifest.files
                    .forEach { item ->
                        val destination =
                            File(
                                system32,
                                item.destinationName,
                            )
                        val backup =
                            File(
                                backups,
                                item.destinationName,
                            )
                        if (backup.isFile) {
                            Files.copy(
                                backup.toPath(),
                                destination.toPath(),
                                StandardCopyOption
                                    .REPLACE_EXISTING,
                            )
                        } else {
                            Files.deleteIfExists(
                                destination.toPath(),
                            )
                        }
                    }

                SafeTreeOps.deleteNoFollow(
                    deployed
                        .deploymentDirectory,
                )
            }
        }

    suspend fun discover(
        prefixPlan: WindowsPrefixPlan,
    ): List<DeployedWindowsRuntimeLayer> =
        withContext(Dispatchers.IO) {
            val prefix =
                prefixPlan.layout
                    ?: return@withContext emptyList()
            val system32 =
                File(
                    prefix.prefixRoot,
                    "drive_c/windows/system32",
                ).canonicalFile

            stateRoot.listFiles()
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
                .mapNotNull {
                    loadDeployment(
                        it,
                        system32,
                    )
                }
                .sortedWith(
                    compareBy<DeployedWindowsRuntimeLayer> {
                        it.manifest.id
                    }.thenBy {
                        it.manifest.version
                    },
                )
        }

    private fun loadDeployment(
        directory: File,
        system32: File,
    ): DeployedWindowsRuntimeLayer? =
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
                    "windows-layer-manifest.json",
                )
            val marker =
                File(
                    directory,
                    "DEPLOYMENT.tsv",
                )
            if (
                !SafeTreeOps.isPlainFile(
                    manifestFile.toPath(),
                ) ||
                !SafeTreeOps.isPlainFile(
                    marker.toPath(),
                )
            ) {
                return@runCatching null
            }

            val manifest =
                WindowsRuntimeLayerManifestCodec
                    .parse(
                        manifestFile.readText(),
                    )
            if (
                WindowsRuntimeLayerManifestValidator
                    .errors(manifest)
                    .isNotEmpty() ||
                WindowsRuntimeLayerTrustPolicy
                    .errors(manifest)
                    .isNotEmpty()
            ) {
                return@runCatching null
            }
            if (
                directory.name !=
                    manifest.version ||
                directory.parentFile?.name !=
                    manifest.id
            ) {
                return@runCatching null
            }

            verifyDeployment(
                manifest,
                system32,
            )

            DeployedWindowsRuntimeLayer(
                manifest = manifest,
                deploymentDirectory =
                    directory,
            )
        }.getOrNull()

    private fun verifyDeployment(
        manifest: WindowsRuntimeLayerManifest,
        system32: File,
    ) {
        manifest.files.forEach { item ->
            val destination =
                File(
                    system32,
                    item.destinationName,
                )
            require(
                SafeTreeOps.isPlainFile(
                    destination.toPath(),
                ),
            ) {
                "WINDOWS_LAYER_DEPLOYED_DLL_MISSING:" +
                    item.destinationName
            }
            val digest =
                destination.inputStream()
                    .buffered()
                    .use {
                        Sha256.digest(
                            it,
                            maxOf(
                                1L,
                                item.bytes,
                            ),
                        )
                    }
            require(
                digest.bytes ==
                    item.bytes &&
                    digest.sha256 ==
                    item.sha256,
            ) {
                "WINDOWS_LAYER_DEPLOYED_DLL_ATTESTATION_FAILED:" +
                    item.destinationName
            }
        }
    }
}
