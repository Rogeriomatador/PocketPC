package dev.pocketpc.core.runtime

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDisplaySurfaceRegistryTest {
    private fun request(
        windowId: Long = 1L,
        generation: Long = 1L,
        width: Int = 64,
        height: Int = 64,
    ) =
        RuntimeBridgeSurfaceRequest(
            windowId = windowId,
            generation = generation,
            width = width,
            height = height,
            pixelFormat =
                RuntimeDisplayBridgePayloadCodec
                    .PIXEL_FORMAT_BGRA8888,
            flags = 0,
        )

    @Test
    fun allocateResizeAndRemoveAreTransactional() {
        val temp =
            Files.createTempDirectory(
                "pocketpc-surface-registry-",
            ).toFile()

        try {
            RuntimeDisplaySurfaceRegistry(
                temp,
            ).use { registry ->
                val first =
                    registry.allocate(
                        request(),
                    ).getOrThrow()
                val firstPath =
                    registry.snapshot()
                        .single()
                        .framebuffer
                        .descriptor
                        .hostFile

                assertTrue(firstPath.isFile)

                val second =
                    registry.allocate(
                        request(
                            generation = 2L,
                            width = 128,
                            height = 96,
                        ),
                    ).getOrThrow()

                assertNotEquals(
                    first.surfaceId,
                    second.surfaceId,
                )
                assertEquals(
                    2L,
                    second.generation,
                )
                assertFalse(
                    firstPath.exists(),
                )
                assertTrue(
                    registry.removeWindow(1L),
                )
                assertTrue(
                    registry.snapshot()
                        .isEmpty(),
                )
                assertFalse(
                    registry.removeWindow(1L),
                )
            }
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun staleGenerationFailsClosed() {
        val temp =
            Files.createTempDirectory(
                "pocketpc-surface-registry-",
            ).toFile()

        try {
            RuntimeDisplaySurfaceRegistry(
                temp,
            ).use { registry ->
                registry.allocate(
                    request(
                        generation = 3L,
                    ),
                ).getOrThrow()

                assertTrue(
                    registry.allocate(
                        request(
                            generation = 3L,
                        ),
                    ).isFailure,
                )
                assertTrue(
                    registry.allocate(
                        request(
                            generation = 2L,
                        ),
                    ).isFailure,
                )
            }
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun frameIdentityAndMonotonicityFailClosed() {
        val temp =
            Files.createTempDirectory(
                "pocketpc-surface-registry-",
            ).toFile()

        try {
            RuntimeDisplaySurfaceRegistry(
                temp,
            ).use { registry ->
                val surface =
                    registry.allocate(
                        request(
                            generation = 4L,
                        ),
                    ).getOrThrow()

                val accepted =
                    RuntimeBridgeFrameReady(
                        windowId = 1L,
                        surfaceId =
                            surface.surfaceId,
                        generation = 4L,
                        frameId = 1L,
                    )

                assertTrue(
                    registry.acceptFrame(
                        accepted,
                    ).isSuccess,
                )
                assertTrue(
                    registry.acceptFrame(
                        accepted,
                    ).isFailure,
                )
                assertTrue(
                    registry.acceptFrame(
                        accepted.copy(
                            frameId = 2L,
                            surfaceId =
                                surface.surfaceId +
                                    1L,
                        ),
                    ).isFailure,
                )
                assertTrue(
                    registry.acceptFrame(
                        accepted.copy(
                            frameId = 2L,
                            generation = 3L,
                        ),
                    ).isFailure,
                )
                assertTrue(
                    registry.acceptFrame(
                        accepted.copy(
                            frameId = 2L,
                        ),
                    ).isSuccess,
                )

                assertEquals(
                    2L,
                    registry.snapshot()
                        .single()
                        .lastFrameId,
                )
            }
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun closeDeletesAllBackingFiles() {
        val temp =
            Files.createTempDirectory(
                "pocketpc-surface-registry-",
            ).toFile()

        try {
            val registry =
                RuntimeDisplaySurfaceRegistry(
                    temp,
                )
            registry.allocate(
                request(
                    windowId = 1L,
                ),
            ).getOrThrow()
            registry.allocate(
                request(
                    windowId = 2L,
                ),
            ).getOrThrow()

            val files =
                registry.snapshot()
                    .map {
                        it.framebuffer
                            .descriptor
                            .hostFile
                    }

            assertTrue(
                files.all {
                    it.isFile
                },
            )

            registry.close()

            assertTrue(
                registry.snapshot()
                    .isEmpty(),
            )
            assertTrue(
                files.none {
                    it.exists()
                },
            )
        } finally {
            temp.deleteRecursively()
        }
    }
    @Test
    fun failedFrameReadDoesNotAdvancePresentationState() {
        val temp =
            Files.createTempDirectory(
                "pocketpc-surface-registry-",
            ).toFile()

        try {
            RuntimeDisplaySurfaceRegistry(
                temp,
            ).use { registry ->
                val surface =
                    registry.allocate(
                        request(
                            generation = 6L,
                            width = 2,
                            height = 2,
                        ),
                    ).getOrThrow()

                val ready =
                    RuntimeBridgeFrameReady(
                        windowId = 1L,
                        surfaceId =
                            surface.surfaceId,
                        generation =
                            surface.generation,
                        frameId = 1L,
                    )

                val backing =
                    registry.snapshot()
                        .single()
                        .framebuffer
                        .descriptor
                        .hostFile

                java.io.RandomAccessFile(
                    backing,
                    "rw",
                ).use { file ->
                    file.write(
                        ByteArray(16) {
                            0x7f
                        },
                    )
                }

                assertTrue(
                    registry.readFrame(
                        ready,
                    ).isSuccess,
                )
                assertEquals(
                    1L,
                    registry.snapshot()
                        .single()
                        .lastFrameId,
                )

                java.io.RandomAccessFile(
                    backing,
                    "rw",
                ).use { file ->
                    file.setLength(4L)
                }

                assertTrue(
                    registry.readFrame(
                        ready.copy(
                            frameId = 2L,
                        ),
                    ).isFailure,
                )
                assertEquals(
                    1L,
                    registry.snapshot()
                        .single()
                        .lastFrameId,
                )
            }
        } finally {
            temp.deleteRecursively()
        }
    }

}
