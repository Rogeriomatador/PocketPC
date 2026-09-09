package dev.pocketpc.core.runtime

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
        val expected = RuntimeBridgeFramePresented(11, 99, 0)
        val payload = RuntimeDisplayBridgePayloadCodec.encodeFramePresented(expected)
        assertEquals(RuntimeDisplayBridgePayloadCodec.FRAME_PRESENTED_BYTES, payload.size)
        assertEquals(expected, RuntimeDisplayBridgePayloadCodec.decodeFramePresented(payload).getOrThrow())
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
        val value = RuntimeBridgeKeyEvent(1, 0, 13, 28, 0, 0)
        assertArrayEquals(RuntimeDisplayBridgePayloadCodec.encodeKeyEvent(value), RuntimeDisplayBridgePayloadCodec.encodeKeyEvent(value))
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

}
