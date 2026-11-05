package dev.pocketpc.core.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

data class WindowsRuntimeLayerVerification(
    val valid: Boolean,
    val errors: List<String>,
)

object WindowsRuntimeLayerPackageVerifier {
    private const val MANIFEST_NAME =
        "windows-layer-manifest.json"

    fun verify(
        packageRoot: File,
        manifest: WindowsRuntimeLayerManifest,
    ): WindowsRuntimeLayerVerification {
        val errors =
            (
                WindowsRuntimeLayerManifestValidator
                    .errors(manifest) +
                    WindowsRuntimeLayerTrustPolicy
                        .errors(manifest)
            ).toMutableList()

        if (errors.isNotEmpty()) {
            return WindowsRuntimeLayerVerification(
                valid = false,
                errors = errors.distinct(),
            )
        }

        val root =
            runCatching {
                packageRoot.canonicalFile
            }.getOrElse {
                return WindowsRuntimeLayerVerification(
                    valid = false,
                    errors =
                        listOf(
                            "WINDOWS_LAYER_PACKAGE_ROOT_INVALID",
                        ),
                )
            }

        if (
            !SafeTreeOps.isPlainDirectory(
                root.toPath(),
            )
        ) {
            return WindowsRuntimeLayerVerification(
                valid = false,
                errors =
                    listOf(
                        "WINDOWS_LAYER_PACKAGE_ROOT_NOT_PLAIN_DIRECTORY",
                    ),
            )
        }

        val allowed =
            manifest.files
                .map { it.path }
                .toMutableSet()
                .apply {
                    add(MANIFEST_NAME)
                }

        manifest.files.forEach { item ->
            var cursor = root
            item.path.split('/').forEach {
                part ->
                cursor = File(cursor, part)
                if (
                    Files.isSymbolicLink(
                        cursor.toPath(),
                    )
                ) {
                    errors +=
                        "WINDOWS_LAYER_SYMLINK_FORBIDDEN:" +
                            item.path
                }
            }

            val candidate =
                runCatching {
                    cursor.canonicalFile
                }.getOrNull()

            if (
                candidate == null ||
                !candidate.path.startsWith(
                    root.path + File.separator,
                ) ||
                !SafeTreeOps.isPlainFile(
                    candidate.toPath(),
                ) ||
                !candidate.canRead()
            ) {
                errors +=
                    "WINDOWS_LAYER_FILE_MISSING_OR_INVALID:" +
                        item.path
                return@forEach
            }

            if (
                candidate.length() !=
                item.bytes
            ) {
                errors +=
                    "WINDOWS_LAYER_FILE_SIZE_MISMATCH:" +
                        item.path
                return@forEach
            }

            val digest =
                candidate.inputStream()
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
            if (digest.sha256 != item.sha256) {
                errors +=
                    "WINDOWS_LAYER_FILE_SHA256_MISMATCH:" +
                        item.path
            }
        }

        Files.walk(root.toPath()).use {
            paths ->
            paths
                .filter {
                    it != root.toPath()
                }
                .forEach { path ->
                    val relative =
                        root.toPath()
                            .relativize(path)
                            .joinToString("/") {
                                it.toString()
                            }

                    if (
                        Files.isSymbolicLink(path)
                    ) {
                        errors +=
                            "WINDOWS_LAYER_SYMLINK_FORBIDDEN:" +
                                relative
                    } else if (
                        Files.isRegularFile(
                            path,
                            LinkOption.NOFOLLOW_LINKS,
                        ) &&
                        relative !in allowed
                    ) {
                        errors +=
                            "WINDOWS_LAYER_UNEXPECTED_FILE:" +
                                relative
                    }
                }
        }

        return WindowsRuntimeLayerVerification(
            valid = errors.isEmpty(),
            errors = errors.distinct(),
        )
    }
}
