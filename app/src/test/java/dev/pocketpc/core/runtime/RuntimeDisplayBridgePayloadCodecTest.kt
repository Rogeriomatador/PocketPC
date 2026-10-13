package dev.pocketpc.core.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDisplayBridgePayloadCodecTest {
    @Test fun pointerRoundTrip() {
        val expected = RuntimeBridgePointerEvent(7, 2, 123, -45, 3, -120, 9)
        val payload = RuntimeDisplayBridgePayloadCodec.encodePointerEvent(expected)
        assertEquals(RuntimeDisplayBridgePayloadCodec.POINTER_EVENT_BYTES, payload.size)
        assertEquals(expected, RuntimeDisplayBridgePayloadCodec.decodePointerEvent(payload).getOrThrow())
    }

    @Test fun keyRoundTrip() {
        val expected = RuntimeBridgeKeyEvent(9, 1, 65, 30, 2, 4)
        val payload = RuntimeDisplayBridgePayloadCodec.encodeKeyEvent(expected)
        assertEquals(RuntimeDisplayBridgePayloadCodec.KEY_EVENT_BYTES, payload.size)
        assertEquals(expected, RuntimeDisplayBridgePayloadCodec.decodeKeyEvent(payload).getOrThrow())
    }

    @Test fun frameAckRoundTrip() {
        val expected =
            RuntimeBridgeFramePresented(
                windowId = 11,
                surfaceId = 22,
                generation = 33,
                frameId = 99,
                status = 0,
            )
        val payload = RuntimeDisplayBridgePayloadCodec.encodeFramePresented(expected)
        assertEquals(RuntimeDisplayBridgePayloadCodec.FRAME_PRESENTED_BYTES, payload.size)
        assertEquals(expected, RuntimeDisplayBridgePayloadCodec.decodeFramePresented(payload).getOrThrow())
    }

    @Test
    fun frameAckRejectsMissingSurfaceIdentity() {
        val result =
            runCatching {
                RuntimeDisplayBridgePayloadCodec
                    .encodeFramePresented(
                        RuntimeBridgeFramePresented(
                            windowId = 1,
                            surfaceId = 0,
                            generation = 1,
                            frameId = 1,
                            status = 0,
                        ),
                    )
            }

        assertTrue(result.isFailure)
    }

    @Test fun invalidPointerIsRejectedBeforeEncoding() {
        val result = runCatching { RuntimeDisplayBridgePayloadCodec.encodePointerEvent(RuntimeBridgePointerEvent(0, 1, 0, 0, 0, 0, 0)) }
        assertTrue(result.isFailure)
    }

    @Test fun malformedPayloadLengthIsRejected() {
        val result = RuntimeDisplayBridgePayloadCodec.decodeKeyEvent(ByteArray(RuntimeDisplayBridgePayloadCodec.KEY_EVENT_BYTES - 1))
        assertTrue(result.isFailure)
    }

    @Test fun encodingIsDeterministic() {
        val value =
            RuntimeBridgeKeyEvent(
                1,
                RuntimeDisplayBridgePayloadCodec.KEY_ACTION_DOWN,
                13,
                28,
                0,
                0,
            )
        assertArrayEquals(RuntimeDisplayBridgePayloadCodec.encodeKeyEvent(value), RuntimeDisplayBridgePayloadCodec.encodeKeyEvent(value))
    }

    @Test
    fun windowCommandRoundTrip() {
        val expected =
            RuntimeBridgeWindowCommand(
                windowId = 42L,
                command =
                    RuntimeDisplayBridgePayloadCodec
                        .WINDOW_COMMAND_CLOSE,
            )

        val payload =
            RuntimeDisplayBridgePayloadCodec
                .encodeWindowCommand(
                    expected,
                )

        assertEquals(
            RuntimeDisplayBridgePayloadCodec
                .WINDOW_COMMAND_BYTES,
            payload.size,
        )
        assertEquals(
            expected,
            RuntimeDisplayBridgePayloadCodec
                .decodeWindowCommand(
                    payload,
                )
                .getOrThrow(),
        )
    }

    @Test
    fun windowCommandRejectsUnknownCommand() {
        val result =
            runCatching {
                RuntimeDisplayBridgePayloadCodec
                    .encodeWindowCommand(
                        RuntimeBridgeWindowCommand(
                            windowId = 1L,
                            command = 6,
                        ),
                    )
            }

        assertTrue(result.isFailure)
    }

    @Test
    fun windowCommandRejectsFlags() {
        val result =
            runCatching {
                RuntimeDisplayBridgePayloadCodec
                    .encodeWindowCommand(
                        RuntimeBridgeWindowCommand(
                            windowId = 1L,
                            command =
                                RuntimeDisplayBridgePayloadCodec
                                    .WINDOW_COMMAND_ACTIVATE,
                            flags = 1,
                        ),
                    )
            }

        assertTrue(result.isFailure)
    }

    @Test
    fun surfaceRequestRoundTrip() {
        val expected =
            RuntimeBridgeSurfaceRequest(
                windowId = 7,
                generation = 2,
                width = 1280,
                height = 720,
                pixelFormat =
                    RuntimeDisplayBridgePayloadCodec
                        .PIXEL_FORMAT_BGRA8888,
                flags = 0,
            )

        val payload =
            RuntimeDisplayBridgePayloadCodec
                .encodeSurfaceRequest(
                    expected,
                )

        assertEquals(
            RuntimeDisplayBridgePayloadCodec
                .SURFACE_REQUEST_BYTES,
            payload.size,
        )
        assertEquals(
            expected,
            RuntimeDisplayBridgePayloadCodec
                .decodeSurfaceRequest(
                    payload,
                )
                .getOrThrow(),
        )
    }

    @Test
    fun surfaceRequestRejectsUnknownFlags() {
        val result =
            runCatching {
                RuntimeDisplayBridgePayloadCodec
                    .encodeSurfaceRequest(
                        RuntimeBridgeSurfaceRequest(
                            windowId = 1,
                            generation = 1,
                            width = 64,
                            height = 64,
                            pixelFormat =
                                RuntimeDisplayBridgePayloadCodec
                                    .PIXEL_FORMAT_BGRA8888,
                            flags = 1,
                        ),
                    )
            }

        assertTrue(result.isFailure)
    }

    @Test
    fun surfaceDescriptorRoundTrip() {
        val expected =
            RuntimeBridgeSurfaceAvailable(
                windowId = 1,
                surfaceId = 2,
                generation = 3,
                width = 64,
                height = 64,
                strideBytes = 256,
                pixelFormat =
                    RuntimeDisplayBridgePayloadCodec
                        .PIXEL_FORMAT_BGRA8888,
                tokenHex =
                    "00112233445566778899aabbccddeeff",
            )
        val payload =
            RuntimeDisplayBridgePayloadCodec
                .encodeSurfaceAvailable(
                    expected,
                )
        assertEquals(
            RuntimeDisplayBridgePayloadCodec
                .SURFACE_AVAILABLE_BYTES,
            payload.size,
        )
        assertEquals(
            expected,
            RuntimeDisplayBridgePayloadCodec
                .decodeSurfaceAvailable(
                    payload,
                )
                .getOrThrow(),
        )
        assertEquals(
            "/tmp/.pocketpc-surface-00112233445566778899aabbccddeeff.bgra",
            expected.guestPath,
        )
    }

    @Test
    fun frameReadyRoundTrip() {
        val expected =
            RuntimeBridgeFrameReady(
                windowId = 1,
                surfaceId = 2,
                generation = 3,
                frameId = 4,
            )
        val payload =
            RuntimeDisplayBridgePayloadCodec
                .encodeFrameReady(
                    expected,
                )
        assertEquals(
            RuntimeDisplayBridgePayloadCodec
                .FRAME_READY_BYTES,
            payload.size,
        )
        assertEquals(
            expected,
            RuntimeDisplayBridgePayloadCodec
                .decodeFrameReady(
                    payload,
                )
                .getOrThrow(),
        )
    }

    @Test
    fun windowGeometryAfterSiblingDecodes() {
        val payload =
            ByteBuffer
                .allocate(
                    RuntimeDisplayBridgePayloadCodec
                        .WINDOW_GEOMETRY_BYTES,
                )
                .order(
                    ByteOrder.LITTLE_ENDIAN,
                )
                .apply {
                    putLong(7)
                    putInt(10)
                    putInt(20)
                    putInt(800)
                    putInt(600)
                    putInt(1)
                    putInt(
                        RuntimeDisplayBridgePayloadCodec
                            .Z_ORDER_AFTER_WINDOW,
                    )
                    putLong(5)
                }
                .array()

        val geometry =
            RuntimeDisplayBridgePayloadCodec
                .decodeWindowGeometry(
                    payload,
                )
                .getOrThrow()

        assertEquals(7L, geometry.windowId)
        assertEquals(
            RuntimeDisplayBridgePayloadCodec
                .Z_ORDER_AFTER_WINDOW,
            geometry.zOrderFlags,
        )
        assertEquals(
            5L,
            geometry.insertAfterWindowId,
        )
    }

    @Test
    fun multipleZOrderFlagsFailClosed() {
        val payload =
            ByteBuffer
                .allocate(
                    RuntimeDisplayBridgePayloadCodec
                        .WINDOW_GEOMETRY_BYTES,
                )
                .order(
                    ByteOrder.LITTLE_ENDIAN,
                )
                .apply {
                    putLong(7)
                    putInt(0)
                    putInt(0)
                    putInt(10)
                    putInt(10)
                    putInt(1)
                    putInt(
                        RuntimeDisplayBridgePayloadCodec
                            .Z_ORDER_TOP or
                            RuntimeDisplayBridgePayloadCodec
                                .Z_ORDER_TOPMOST,
                    )
                    putLong(0)
                }
                .array()

        assertTrue(
            RuntimeDisplayBridgePayloadCodec
                .decodeWindowGeometry(
                    payload,
                )
                .isFailure,
        )
    }

    @Test
    fun afterWindowRejectsSelfReference() {
        val payload =
            ByteBuffer
                .allocate(
                    RuntimeDisplayBridgePayloadCodec
                        .WINDOW_GEOMETRY_BYTES,
                )
                .order(
                    ByteOrder.LITTLE_ENDIAN,
                )
                .apply {
                    putLong(7)
                    putInt(0)
                    putInt(0)
                    putInt(10)
                    putInt(10)
                    putInt(1)
                    putInt(
                        RuntimeDisplayBridgePayloadCodec
                            .Z_ORDER_AFTER_WINDOW,
                    )
                    putLong(7)
                }
                .array()

        assertTrue(
            RuntimeDisplayBridgePayloadCodec
                .decodeWindowGeometry(
                    payload,
                )
                .isFailure,
        )
    }

    @Test
    fun keyRejectsUnknownModifierBits() {
        val result =
            runCatching {
                RuntimeDisplayBridgePayloadCodec
                    .encodeKeyEvent(
                        RuntimeBridgeKeyEvent(
                            windowId = 1,
                            action =
                                RuntimeDisplayBridgePayloadCodec
                                    .KEY_ACTION_DOWN,
                            keyCode = 0x41,
                            scanCode = 0x1e,
                            modifiers = 1 shl 8,
                            repeatCount = 0,
                        ),
                    )
            }

        assertTrue(result.isFailure)
    }

    @Test
    fun pointerRejectsUnknownModifierBits() {
        val result =
            runCatching {
                RuntimeDisplayBridgePayloadCodec
                    .encodePointerEvent(
                        RuntimeBridgePointerEvent(
                            windowId = 1,
                            action =
                                RuntimeDisplayBridgePayloadCodec
                                    .POINTER_ACTION_MOVE,
                            x = 1,
                            y = 1,
                            buttons = 0,
                            verticalScroll = 0,
                            modifiers = 1 shl 8,
                        ),
                    )
            }

        assertTrue(result.isFailure)
    }
}
