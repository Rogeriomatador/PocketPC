package dev.pocketpc.core.runtime

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

data class RuntimeDisplaySharedFramebufferDescriptor(
    val surface:
        RuntimeBridgeSurfaceAvailable,
    val hostFile: File,
    val bytes: Long,
)

class RuntimeDisplaySharedFramebuffer private constructor(
    val descriptor:
        RuntimeDisplaySharedFramebufferDescriptor,
) : Closeable {
    fun validateSmokePattern(): Result<Unit> =
        runCatching {
            val surface =
                descriptor.surface
            require(
                descriptor.hostFile.length() ==
                    descriptor.bytes,
            ) {
                "DISPLAY_BRIDGE_FRAMEBUFFER_SIZE_CHANGED"
            }

            RandomAccessFile(
                descriptor.hostFile,
                "r",
            ).use { file ->
                val row =
                    ByteArray(
                        surface.strideBytes,
                    )
                for (
                    y in
                    0 until surface.height
                ) {
                    file.readFully(row)
                    for (
                        x in
                        0 until surface.width
                    ) {
                        val offset =
                            x * 4
                        val expectedB =
                            x and 0xff
                        val expectedG =
                            y and 0xff
                        val expectedR =
                            (x xor y) and
                                0xff

                        require(
                            row[
                                offset
                            ].toInt() and
                                0xff ==
                                expectedB &&
                                row[
                                    offset +
                                        1
                                ].toInt() and
                                0xff ==
                                expectedG &&
                                row[
                                    offset +
                                        2
                                ].toInt() and
                                0xff ==
                                expectedR &&
                                row[
                                    offset +
                                        3
                                ].toInt() and
                                0xff ==
                                0xff
                        ) {
                            "DISPLAY_BRIDGE_FRAMEBUFFER_PATTERN_MISMATCH:" +
                                x +
                                "," +
                                y
                        }
                    }

                    for (
                        offset in
                        surface.width * 4 until
                            surface.strideBytes
                    ) {
                        require(
                            row[offset] ==
                                0.toByte(),
                        ) {
                            "DISPLAY_BRIDGE_FRAMEBUFFER_PADDING_CHANGED"
                        }
                    }
                }

                require(
                    file.filePointer ==
                        descriptor.bytes,
                ) {
                    "DISPLAY_BRIDGE_FRAMEBUFFER_READ_SIZE_MISMATCH"
                }
            }
        }

    override fun close() {
        runCatching {
            descriptor.hostFile.delete()
        }
    }

    companion object {
        private const val MAX_FRAMEBUFFER_BYTES =
            64L * 1024L * 1024L
        private val random =
            SecureRandom()

        fun createSmoke(
            hostTempDirectory: File,
            windowId: Long = 1L,
            surfaceId: Long = 1L,
            generation: Long = 1L,
            width: Int = 64,
            height: Int = 64,
        ): RuntimeDisplaySharedFramebuffer {
            require(
                SafeTreeOps.isPlainDirectory(
                    hostTempDirectory.toPath(),
                ),
            ) {
                "DISPLAY_BRIDGE_TEMP_DIRECTORY_INVALID"
            }
            require(
                width in 1..4096 &&
                    height in 1..4096,
            ) {
                "DISPLAY_BRIDGE_FRAMEBUFFER_DIMENSION_INVALID"
            }

            val stride =
                Math.multiplyExact(
                    width,
                    4,
                )
            val bytes =
                Math.multiplyExact(
                    stride.toLong(),
                    height.toLong(),
                )
            require(
                bytes in
                    1..MAX_FRAMEBUFFER_BYTES,
            ) {
                "DISPLAY_BRIDGE_FRAMEBUFFER_BYTES_INVALID"
            }

            val tokenBytes =
                ByteArray(16)
                    .also(
                        random::nextBytes,
                    )
            val tokenHex =
                tokenBytes.joinToString(
                    "",
                ) {
                    "%02x".format(
                        it.toInt() and
                            0xff,
                    )
                }
            val file =
                File(
                    hostTempDirectory,
                    ".pocketpc-surface-" +
                        tokenHex +
                        ".bgra",
                )
            require(!file.exists()) {
                "DISPLAY_BRIDGE_FRAMEBUFFER_COLLISION"
            }

            RandomAccessFile(
                file,
                "rw",
            ).use {
                it.setLength(bytes)
                it.fd.sync()
            }
            require(
                SafeTreeOps.isPlainFile(
                    file.toPath(),
                ),
            ) {
                "DISPLAY_BRIDGE_FRAMEBUFFER_NOT_PLAIN_FILE"
            }

            val surface =
                RuntimeBridgeSurfaceAvailable(
                    windowId = windowId,
                    surfaceId = surfaceId,
                    generation =
                        generation,
                    width = width,
                    height = height,
                    strideBytes =
                        stride,
                    pixelFormat =
                        RuntimeDisplayBridgePayloadCodec
                            .PIXEL_FORMAT_BGRA8888,
                    tokenHex =
                        tokenHex,
                )

            return RuntimeDisplaySharedFramebuffer(
                RuntimeDisplaySharedFramebufferDescriptor(
                    surface = surface,
                    hostFile = file,
                    bytes = bytes,
                ),
            )
        }
    }
}
