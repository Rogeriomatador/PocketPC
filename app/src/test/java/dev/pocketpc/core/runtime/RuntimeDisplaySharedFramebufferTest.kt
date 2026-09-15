package dev.pocketpc.core.runtime

import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDisplaySharedFramebufferTest {
    @Test
    fun smokePatternValidatesAndMutationFails() {
        val temp =
            Files.createTempDirectory(
                "pocketpc-framebuffer-",
            ).toFile()
        val framebuffer =
            RuntimeDisplaySharedFramebuffer
                .createSmoke(
                    hostTempDirectory =
                        temp,
                )
        try {
            val surface =
                framebuffer
                    .descriptor
                    .surface
            RandomAccessFile(
                framebuffer
                    .descriptor
                    .hostFile,
                "rw",
            ).use { file ->
                val row =
                    ByteArray(
                        surface.strideBytes,
                    )
                for (
                    y in
                    0 until surface.height
                ) {
                    row.fill(0)
                    for (
                        x in
                        0 until surface.width
                    ) {
                        val offset =
                            x * 4
                        row[offset] =
                            (x and 0xff)
                                .toByte()
                        row[
                            offset + 1
                        ] =
                            (y and 0xff)
                                .toByte()
                        row[
                            offset + 2
                        ] =
                            (
                                (x xor y) and
                                    0xff
                                ).toByte()
                        row[
                            offset + 3
                        ] =
                            0xff.toByte()
                    }
                    file.write(row)
                }
                file.fd.sync()
            }

            assertTrue(
                framebuffer
                    .validateSmokePattern()
                    .isSuccess,
            )

            RandomAccessFile(
                framebuffer
                    .descriptor
                    .hostFile,
                "rw",
            ).use {
                it.seek(0)
                it.write(0x7f)
                it.fd.sync()
            }

            assertTrue(
                framebuffer
                    .validateSmokePattern()
                    .isFailure,
            )
        } finally {
            val file =
                framebuffer
                    .descriptor
                    .hostFile
            framebuffer.close()
            assertFalse(file.exists())
            SafeTreeOps.deleteNoFollow(temp)
        }
    }
}
