package dev.pocketpc.core.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

data class GuestToolFileVerification(
    val path: String,
    val valid: Boolean,
    val message: String,
)

data class GuestToolPackageVerification(
    val valid: Boolean,
    val errors: List<String>,
    val files: List<GuestToolFileVerification>,
)

object GuestToolPackageVerifier {
    private const val MANIFEST_NAME = "guest-tool-manifest.json"

    fun verify(
        packageRoot: File,
        manifest: GuestToolManifest,
    ): GuestToolPackageVerification {
        val manifestErrors = GuestToolManifestValidator.errors(manifest)
        if (manifestErrors.isNotEmpty()) {
            return GuestToolPackageVerification(
                valid = false,
                errors = manifestErrors,
                files = emptyList(),
            )
        }

        val root =
            runCatching { packageRoot.canonicalFile }
                .getOrElse {
                    return GuestToolPackageVerification(
                        valid = false,
                        errors = listOf("GUEST_TOOL_PACKAGE_ROOT_INVALID"),
                        files = emptyList(),
                    )
                }

        if (!SafeTreeOps.isPlainDirectory(root.toPath())) {
            return GuestToolPackageVerification(
                valid = false,
                errors = listOf("GUEST_TOOL_PACKAGE_ROOT_NOT_PLAIN_DIRECTORY"),
                files = emptyList(),
            )
        }

        val records = manifest.files.map { item ->
            verifyFile(root, item)
        }
        val errors =
            records.filterNot { it.valid }
                .map { it.message }
                .toMutableList()

        val allowed =
            manifest.files.map { it.path }.toMutableSet().apply {
                add(MANIFEST_NAME)
            }

        Files.walk(root.toPath()).use { paths ->
            paths
                .filter { it != root.toPath() }
                .forEach { path ->
                    val relative =
                        root.toPath()
                            .relativize(path)
                            .joinToString("/") { it.toString() }

                    if (Files.isSymbolicLink(path)) {
                        errors += "GUEST_TOOL_SYMLINK_FORBIDDEN:" + relative
                    } else if (
                        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                        relative !in allowed
                    ) {
                        errors += "GUEST_TOOL_UNEXPECTED_FILE:" + relative
                    }
                }
        }

        return GuestToolPackageVerification(
            valid = errors.isEmpty(),
            errors = errors.distinct(),
            files = records,
        )
    }

    private fun verifyFile(
        root: File,
        item: GuestToolFile,
    ): GuestToolFileVerification {
        var cursor = root
        item.path.split('/').forEach { part ->
            cursor = File(cursor, part)
            if (Files.isSymbolicLink(cursor.toPath())) {
                return GuestToolFileVerification(
                    path = item.path,
                    valid = false,
                    message = "GUEST_TOOL_SYMLINK_FORBIDDEN:" + item.path,
                )
            }
        }

        val candidate =
            runCatching { cursor.canonicalFile }.getOrNull()
        if (
            candidate == null ||
            candidate.path != root.path + File.separator + item.path.replace('/', File.separatorChar) ||
            !SafeTreeOps.isPlainFile(candidate.toPath()) ||
            !candidate.canRead()
        ) {
            return GuestToolFileVerification(
                path = item.path,
                valid = false,
                message = "GUEST_TOOL_FILE_MISSING_OR_INVALID:" + item.path,
            )
        }

        if (candidate.length() != item.bytes) {
            return GuestToolFileVerification(
                path = item.path,
                valid = false,
                message = "GUEST_TOOL_FILE_SIZE_MISMATCH:" + item.path,
            )
        }

        val digest =
            candidate.inputStream().buffered().use {
                Sha256.digest(
                    input = it,
                    maxBytes = item.bytes,
                )
            }
        if (digest.sha256 != item.sha256) {
            return GuestToolFileVerification(
                path = item.path,
                valid = false,
                message = "GUEST_TOOL_FILE_SHA256_MISMATCH:" + item.path,
            )
        }

        if (item.executable && !candidate.canExecute()) {
            return GuestToolFileVerification(
                path = item.path,
                valid = false,
                message = "GUEST_TOOL_FILE_NOT_EXECUTABLE:" + item.path,
            )
        }

        return GuestToolFileVerification(
            path = item.path,
            valid = true,
            message = "ATTEST_OK",
        )
    }
}
