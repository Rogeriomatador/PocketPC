package dev.pocketpc.core.runtime

import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDisplayFrameReaderTest {
    @Test
    fun bgraRowsDecodeToArgb() {
        val temp =
            Files.createTempDirectory(
                "pocketpc-frame-reader-",
            ).toFile()

        try {
            val framebuffer =
                RuntimeDisplaySharedFramebuffer
                    .create(
                        hostTempDirectory =
                            temp,
                        windowId = 1L,
                        surfaceId = 2L,
                        generation = 3L,
                        width = 2,
                        height = 2,
                    )

            framebuffer.use {
                RandomAccessFile(
                    framebuffer
                        .descriptor
                        .hostFile,
                    "rw",
                ).use { file ->
                    file.write(
                        byteArrayOf(
                            0x03,
                            0x02,
                            0x01,
                            0x04,
                            0x30,
                            0x20,
                            0x10,
                            0x40,
                            0x33,
                            0x22,
                            0x11,
                            0x44,
                            0x63,
                            0x52,
                            0x41,
                            0x74,
                        ),
                    )
                }

                val frame =
                    RuntimeDisplayFrameReader
                        .read(
                            framebuffer,
                        )
                        .getOrThrow()

                assertEquals(
                    2,
                    frame.width,
                )
                assertEquals(
                    2,
                    frame.height,
                )
                assertArrayEquals(
                    intArrayOf(
                        0x04010203,
                        0x40102030,
                        0x44112233,
                        0x74415263,
                    ),
                    frame.argb,
                )
            }
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun changedBackingSizeFailsClosed() {
        val temp =
            Files.createTempDirectory(
                "pocketpc-frame-reader-",
            ).toFile()

        try {
            val framebuffer =
                RuntimeDisplaySharedFramebuffer
                    .create(
                        hostTempDirectory =
                            temp,
                        windowId = 1L,
                        surfaceId = 1L,
                        generation = 1L,
                        width = 2,
                        height = 2,
                    )

            framebuffer.use {
                RandomAccessFile(
                    framebuffer
                        .descriptor
                        .hostFile,
                    "rw",
                ).use {
                    it.setLength(4L)
                }

                assertTrue(
                    RuntimeDisplayFrameReader
                        .read(
                            framebuffer,
                        )
                        .isFailure,
                )
            }
        } finally {
            temp.deleteRecursively()
        }
    }
}
