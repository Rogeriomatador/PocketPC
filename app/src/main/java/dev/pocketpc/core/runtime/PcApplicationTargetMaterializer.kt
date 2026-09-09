package dev.pocketpc.core.runtime

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class MaterializedPcApplicationTarget(
    val source: PcApplicationTarget,
    val hostFile: File,
    val guestPath: String,
    val bytes: Long,
    val sha256: String,
)

class PcApplicationTargetMaterializer(
    context: Context,
) {
    private val appContext =
        context.applicationContext

    fun materialize(
        target: PcApplicationTarget,
        runtimeHome: File,
    ): Result<MaterializedPcApplicationTarget> =
        runCatching {
            require(
                target.uri.isNotBlank(),
            ) {
                "PC_TARGET_URI_MISSING"
            }
            require(
                target.sizeBytes in
                    0L..MAX_TARGET_BYTES,
            ) {
                "PC_TARGET_DECLARED_SIZE_INVALID"
            }

            val sourceUri =
                Uri.parse(target.uri)
            require(
                sourceUri.scheme ==
                    "content"
            ) {
                "PC_TARGET_URI_SCHEME_INVALID"
            }

            val canonicalHome =
                runtimeHome.canonicalFile
            require(
                SafeTreeOps.isPlainDirectory(
                    canonicalHome.toPath(),
                ),
            ) {
                "PC_TARGET_RUNTIME_HOME_INVALID"
            }

            val targetRoot =
                File(
                    canonicalHome,
                    TARGET_DIRECTORY,
                )
            require(
                targetRoot.mkdirs() ||
                    targetRoot.isDirectory
            ) {
                "PC_TARGET_DIRECTORY_CREATE_FAILED"
            }

            val canonicalRoot =
                targetRoot.canonicalFile
            require(
                canonicalRoot.parentFile ==
                    canonicalHome &&
                    SafeTreeOps.isPlainDirectory(
                        canonicalRoot.toPath(),
                    )
            ) {
                "PC_TARGET_DIRECTORY_INVALID"
            }

            val safeName =
                sanitizeFileName(
                    target.fileName,
                )

            val temporary =
                Files.createTempFile(
                    canonicalRoot.toPath(),
                    ".target-",
                    ".part",
                )
            var moved = false

            try {
                val digest =
                    MessageDigest.getInstance(
                        "SHA-256",
                    )
                var copied = 0L

                appContext
                    .contentResolver
                    .openInputStream(
                        sourceUri,
                    )
                    ?.use { input ->
                        Files.newOutputStream(
                            temporary,
                        ).use { output ->
                            val buffer =
                                ByteArray(
                                    COPY_BUFFER_BYTES,
                                )
                            while (true) {
                                val read =
                                    input.read(buffer)
                                if (read < 0)
                                    break
                                if (read == 0)
                                    continue

                                copied =
                                    Math.addExact(
                                        copied,
                                        read.toLong(),
                                    )
                                require(
                                    copied <=
                                        MAX_TARGET_BYTES,
                                ) {
                                    "PC_TARGET_SIZE_LIMIT_EXCEEDED"
                                }

                                digest.update(
                                    buffer,
                                    0,
                                    read,
                                )
                                output.write(
                                    buffer,
                                    0,
                                    read,
                                )
                            }
                            output.flush()
                        }
                    } ?: error(
                    "PC_TARGET_OPEN_FAILED",
                )

                require(copied > 0L) {
                    "PC_TARGET_EMPTY"
                }
                if (target.sizeBytes > 0L) {
                    require(
                        copied ==
                            target.sizeBytes,
                    ) {
                        "PC_TARGET_SIZE_CHANGED"
                    }
                }

                val sha256 =
                    digest.digest()
                        .joinToString("") {
                            byte ->
                            "%02x".format(
                                byte.toInt() and
                                    0xff,
                            )
                        }
                val finalName =
                    sha256.take(
                        HASH_PREFIX_CHARS,
                    ) +
                        "-" +
                        safeName
                val destination =
                    File(
                        canonicalRoot,
                        finalName,
                    ).canonicalFile
                require(
                    destination.parentFile ==
                        canonicalRoot
                ) {
                    "PC_TARGET_PATH_ESCAPED"
                }

                try {
                    Files.move(
                        temporary,
                        destination.toPath(),
                        StandardCopyOption
                            .ATOMIC_MOVE,
                        StandardCopyOption
                            .REPLACE_EXISTING,
                    )
                } catch (
                    _: AtomicMoveNotSupportedException
                ) {
                    Files.move(
                        temporary,
                        destination.toPath(),
                        StandardCopyOption
                            .REPLACE_EXISTING,
                    )
                }
                moved = true

                require(
                    SafeTreeOps.isPlainFile(
                        destination.toPath(),
                    ) &&
                        destination.length() ==
                            copied
                ) {
                    "PC_TARGET_FINAL_FILE_INVALID"
                }

                val guestPath =
                    "/home/pocket/" +
                        TARGET_DIRECTORY +
                        "/" +
                        destination.name
                require(
                    RuntimeBindPolicy
                        .normalizeGuestPath(
                            guestPath,
                        ) ==
                        guestPath
                ) {
                    "PC_TARGET_GUEST_PATH_INVALID"
                }

                MaterializedPcApplicationTarget(
                    source = target,
                    hostFile = destination,
                    guestPath = guestPath,
                    bytes = copied,
                    sha256 = sha256,
                )
            } finally {
                if (!moved) {
                    runCatching {
                        Files.deleteIfExists(
                            temporary,
                        )
                    }
                }
            }
        }

    companion object {
        private const val
            TARGET_DIRECTORY =
            "windows-targets"
        private const val
            COPY_BUFFER_BYTES =
            128 * 1024
        private const val
            HASH_PREFIX_CHARS =
            16
        private const val
            MAX_TARGET_BYTES =
            16L * 1024L * 1024L * 1024L

        internal fun sanitizeFileName(
            raw: String,
        ): String {
            val base =
                raw.trim()
                    .take(240)
                    .map {
                        character ->
                        if (
                            character.isLetterOrDigit() ||
                            character == '.' ||
                            character == '-' ||
                            character == '_'
                        ) {
                            character
                        } else {
                            '_'
                        }
                    }
                    .joinToString("")
                    .trim('.')

            return base.ifBlank {
                "program.exe"
            }
        }
    }
}
