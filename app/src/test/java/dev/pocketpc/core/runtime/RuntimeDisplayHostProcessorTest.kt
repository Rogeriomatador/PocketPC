package dev.pocketpc.core.runtime

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDisplayHostProcessorTest {
    private class FakeEndpoint(
        override val negotiatedCapabilities:
            Int =
            RuntimeDisplayBridgeCapabilities
                .WINDOW_SURFACE or
                RuntimeDisplayBridgeCapabilities
                    .FRAME_ACK,
    ) : RuntimeDisplayBridgeEndpoint {
        val inbound =
            ArrayDeque<
                RuntimeDisplayBridgeFrame
            >()
        val surfaces =
            mutableListOf<
                RuntimeBridgeSurfaceAvailable
            >()
        val acknowledgements =
            mutableListOf<
                RuntimeBridgeFramePresented
            >()
        val pointers =
            mutableListOf<
                RuntimeBridgePointerEvent
            >()
        val keys =
            mutableListOf<
                RuntimeBridgeKeyEvent
            >()
        val windowCommands =
            mutableListOf<
                RuntimeBridgeWindowCommand
            >()

        override fun readFrame():
            Result<RuntimeDisplayBridgeFrame> =
            if (inbound.isEmpty()) {
                Result.failure(
                    IllegalStateException(
                        "FAKE_ENDPOINT_EMPTY",
                    ),
                )
            } else {
                Result.success(
                    inbound.removeFirst(),
                )
            }

        override fun sendSurfaceAvailable(
            surface:
                RuntimeBridgeSurfaceAvailable,
        ) {
            surfaces += surface
        }

        override fun sendPointer(
            event:
                RuntimeBridgePointerEvent,
        ) {
            pointers += event
        }

        override fun sendKey(
            event:
                RuntimeBridgeKeyEvent,
        ) {
            keys += event
        }

        override fun sendWindowCommand(
            command:
                RuntimeBridgeWindowCommand,
        ) {
            windowCommands += command
        }

        override fun sendFramePresented(
            event:
                RuntimeBridgeFramePresented,
        ) {
            acknowledgements += event
        }
    }

    private fun createFrame(
        sequence: Long,
        windowId: Long = 1L,
    ) =
        RuntimeDisplayBridgeFrame(
            type =
                RuntimeDisplayBridgeMessageType
                    .WINDOW_CREATE,
            sequence = sequence,
            payload =
                ByteBuffer
                    .allocate(
                        RuntimeDisplayBridgePayloadCodec
                            .WINDOW_CREATE_BYTES,
                    )
                    .order(
                        ByteOrder.LITTLE_ENDIAN,
                    )
                    .apply {
                        putLong(windowId)
                        putLong(0L)
                        putInt(0)
                        putInt(2)
                        putInt(2)
                    }
                    .array(),
        )

    private fun surfaceRequestFrame(
        sequence: Long,
        generation: Long = 1L,
    ) =
        RuntimeDisplayBridgeFrame(
            type =
                RuntimeDisplayBridgeMessageType
                    .SURFACE_REQUEST,
            sequence = sequence,
            payload =
                RuntimeDisplayBridgePayloadCodec
                    .encodeSurfaceRequest(
                        RuntimeBridgeSurfaceRequest(
                            windowId = 1L,
                            generation =
                                generation,
                            width = 2,
                            height = 2,
                            pixelFormat =
                                RuntimeDisplayBridgePayloadCodec
                                    .PIXEL_FORMAT_BGRA8888,
                            flags = 0,
                        ),
                    ),
        )

    private fun frameReadyFrame(
        sequence: Long,
        surface:
            RuntimeBridgeSurfaceAvailable,
        frameId: Long = 1L,
    ) =
        RuntimeDisplayBridgeFrame(
            type =
                RuntimeDisplayBridgeMessageType
                    .FRAME_READY,
            sequence = sequence,
            payload =
                RuntimeDisplayBridgePayloadCodec
                    .encodeFrameReady(
                        RuntimeBridgeFrameReady(
                            windowId =
                                surface.windowId,
                            surfaceId =
                                surface.surfaceId,
                            generation =
                                surface.generation,
                            frameId = frameId,
                        ),
                    ),
        )

    private fun destroyFrame(
        sequence: Long,
    ) =
        RuntimeDisplayBridgeFrame(
            type =
                RuntimeDisplayBridgeMessageType
                    .WINDOW_DESTROY,
            sequence = sequence,
            payload =
                ByteBuffer
                    .allocate(
                        RuntimeDisplayBridgePayloadCodec
                            .WINDOW_DESTROY_BYTES,
                    )
                    .order(
                        ByteOrder.LITTLE_ENDIAN,
                    )
                    .putLong(1L)
                    .array(),
        )

    @Test
    fun processesSurfaceFrameAckAndDestroy() {
        val temp =
            Files.createTempDirectory(
                "pocketpc-host-processor-",
            ).toFile()

        try {
            val endpoint =
                FakeEndpoint()
            endpoint.inbound +=
                createFrame(1L)
            endpoint.inbound +=
                surfaceRequestFrame(2L)

            RuntimeDisplayHostProcessor(
                endpoint,
                temp,
            ).use { processor ->
                assertTrue(
                    processor.processNext()
                        .isSuccess,
                )
                val surfaceStep =
                    processor.processNext()
                        .getOrThrow()

                assertTrue(
                    surfaceStep.event is
                        RuntimeDisplayBridgeEvent
                            .SurfaceRequested,
                )
                assertEquals(
                    1,
                    endpoint.surfaces.size,
                )

                val surface =
                    endpoint.surfaces.single()
                val backing =
                    processor.surfacesSnapshot()
                        .single()
                        .framebuffer
                        .descriptor
                        .hostFile

                RandomAccessFile(
                    backing,
                    "rw",
                ).use { file ->
                    file.write(
                        byteArrayOf(
                            0x03,
                            0x02,
                            0x01,
                            0x7f,
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

                endpoint.inbound +=
                    frameReadyFrame(
                        3L,
                        surface,
                    )

                val frameStep =
                    processor.processNext()
                        .getOrThrow()
                val presented =
                    frameStep.presentedFrame

                assertNotNull(presented)
                assertEquals(
                    0x7f010203,
                    presented!!
                        .pixels
                        .argb[0],
                )
                assertNotNull(
                    processor.pendingFrame(),
                )
                assertTrue(
                    processor.processNext()
                        .isFailure,
                )

                val acknowledged =
                    processor
                        .acknowledgePendingFrame()
                        .getOrThrow()

                assertEquals(
                    1L,
                    acknowledged.frameId,
                )
                assertNull(
                    processor.pendingFrame(),
                )
                assertEquals(
                    RuntimeDisplayHostProcessor
                        .FRAME_STATUS_PRESENTED,
                    endpoint
                        .acknowledgements
                        .single()
                        .status,
                )

                endpoint.inbound +=
                    destroyFrame(4L)
                assertTrue(
                    processor.processNext()
                        .isSuccess,
                )
                assertTrue(
                    processor.windowsSnapshot()
                        .isEmpty(),
                )
                assertTrue(
                    processor.surfacesSnapshot()
                        .isEmpty(),
                )
            }
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun corruptedFrameSendsRejectedAck() {
        val temp =
            Files.createTempDirectory(
                "pocketpc-host-processor-",
            ).toFile()

        try {
            val endpoint =
                FakeEndpoint()
            endpoint.inbound +=
                createFrame(1L)
            endpoint.inbound +=
                surfaceRequestFrame(2L)

            RuntimeDisplayHostProcessor(
                endpoint,
                temp,
            ).use { processor ->
                processor.processNext()
                    .getOrThrow()
                processor.processNext()
                    .getOrThrow()

                val surface =
                    endpoint.surfaces.single()
                val backing =
                    processor.surfacesSnapshot()
                        .single()
                        .framebuffer
                        .descriptor
                        .hostFile

                RandomAccessFile(
                    backing,
                    "rw",
                ).use {
                    it.setLength(4L)
                }

                endpoint.inbound +=
                    frameReadyFrame(
                        3L,
                        surface,
                    )

                assertTrue(
                    processor.processNext()
                        .isFailure,
                )
                assertNull(
                    processor.pendingFrame(),
                )
                assertEquals(
                    RuntimeDisplayHostProcessor
                        .FRAME_STATUS_REJECTED,
                    endpoint
                        .acknowledgements
                        .single()
                        .status,
                )
                assertEquals(
                    0L,
                    processor.surfacesSnapshot()
                        .single()
                        .lastFrameId,
                )
            }
        } finally {
            temp.deleteRecursively()
        }
    }
    @Test
    fun windowCommandIsForwardedToEndpoint() {
        val temp =
            Files.createTempDirectory(
                "pocketpc-display-host-command",
            )
        try {
            val endpoint =
                FakeEndpoint()
            val processor =
                RuntimeDisplayHostProcessor(
                    endpoint =
                        endpoint,
                    hostTempDirectory =
                        temp.toFile(),
                )

            val command =
                RuntimeBridgeWindowCommand(
                    windowId = 7L,
                    command =
                        RuntimeDisplayBridgePayloadCodec
                            .WINDOW_COMMAND_CLOSE,
                )

            processor.sendWindowCommand(
                command,
            )

            assertEquals(
                listOf(command),
                endpoint.windowCommands,
            )

            processor.close()
        } finally {
            temp.toFile()
                .deleteRecursively()
        }
    }

}
