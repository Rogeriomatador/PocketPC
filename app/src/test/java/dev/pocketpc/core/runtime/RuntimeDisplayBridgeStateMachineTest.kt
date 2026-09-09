package dev.pocketpc.core.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDisplayBridgeStateMachineTest {
    private fun payload(size: Int, block: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(block).array()

    @Test fun createsMovesAndDestroysWindowInOrder() {
        val machine = RuntimeDisplayBridgeStateMachine(RuntimeDisplayBridgeCapabilities.WINDOW_SURFACE)
        val create = payload(RuntimeDisplayBridgePayloadCodec.WINDOW_CREATE_BYTES) {
            putLong(1); putLong(0); putInt(0); putInt(800); putInt(600)
        }
        assertTrue(machine.apply(RuntimeDisplayBridgeFrame(RuntimeDisplayBridgeMessageType.WINDOW_CREATE, 1, create)).isSuccess)
        val geometry = payload(RuntimeDisplayBridgePayloadCodec.WINDOW_GEOMETRY_BYTES) {
            putLong(1); putInt(10); putInt(20); putInt(640); putInt(480); putInt(1); putInt(0)
        }
        assertTrue(machine.apply(RuntimeDisplayBridgeFrame(RuntimeDisplayBridgeMessageType.WINDOW_GEOMETRY, 2, geometry)).isSuccess)
        assertEquals(640, machine.snapshot().single().geometry?.width)
        val destroy = payload(RuntimeDisplayBridgePayloadCodec.WINDOW_DESTROY_BYTES) { putLong(1) }
        assertTrue(machine.apply(RuntimeDisplayBridgeFrame(RuntimeDisplayBridgeMessageType.WINDOW_DESTROY, 3, destroy)).isSuccess)
        assertTrue(machine.snapshot().isEmpty())
    }

    @Test fun sequenceGapFailsClosed() {
        val machine = RuntimeDisplayBridgeStateMachine(RuntimeDisplayBridgeCapabilities.WINDOW_SURFACE)
        val create = payload(RuntimeDisplayBridgePayloadCodec.WINDOW_CREATE_BYTES) {
            putLong(1); putLong(0); putInt(0); putInt(1); putInt(1)
        }
        assertTrue(machine.apply(RuntimeDisplayBridgeFrame(RuntimeDisplayBridgeMessageType.WINDOW_CREATE, 2, create)).isFailure)
        assertTrue(machine.snapshot().isEmpty())
    }

    @Test fun hostToGuestMessageIsRejectedAsWrongDirection() {
        val machine = RuntimeDisplayBridgeStateMachine(RuntimeDisplayBridgeCapabilities.POINTER)
        val pointer = RuntimeDisplayBridgePayloadCodec.encodePointerEvent(RuntimeBridgePointerEvent(1,0,0,0,0,0,0))
        assertTrue(machine.apply(RuntimeDisplayBridgeFrame(RuntimeDisplayBridgeMessageType.POINTER_EVENT, 1, pointer)).isFailure)
    }

    @Test fun childPreventsParentDestroy() {
        val machine = RuntimeDisplayBridgeStateMachine(RuntimeDisplayBridgeCapabilities.WINDOW_SURFACE)
        fun create(id:Long,parent:Long,seq:Long)=machine.apply(RuntimeDisplayBridgeFrame(RuntimeDisplayBridgeMessageType.WINDOW_CREATE,seq,payload(RuntimeDisplayBridgePayloadCodec.WINDOW_CREATE_BYTES){putLong(id);putLong(parent);putInt(0);putInt(10);putInt(10)}))
        assertTrue(create(1,0,1).isSuccess); assertTrue(create(2,1,2).isSuccess)
        val destroy = payload(RuntimeDisplayBridgePayloadCodec.WINDOW_DESTROY_BYTES){putLong(1)}
        assertTrue(machine.apply(RuntimeDisplayBridgeFrame(RuntimeDisplayBridgeMessageType.WINDOW_DESTROY,3,destroy)).isFailure)
        assertEquals(2,machine.snapshot().size)
    }
    @Test
    fun frameReadyAdvancesGuestSequence() {
        val machine =
            RuntimeDisplayBridgeStateMachine(
                RuntimeDisplayBridgeCapabilities
                    .WINDOW_SURFACE,
            )
        val create =
            payload(
                RuntimeDisplayBridgePayloadCodec
                    .WINDOW_CREATE_BYTES,
            ) {
                putLong(1)
                putLong(0)
                putInt(0)
                putInt(64)
                putInt(64)
            }
        assertTrue(
            machine.apply(
                RuntimeDisplayBridgeFrame(
                    RuntimeDisplayBridgeMessageType
                        .WINDOW_CREATE,
                    1,
                    create,
                ),
            ).isSuccess,
        )

        val ready =
            RuntimeDisplayBridgePayloadCodec
                .encodeFrameReady(
                    RuntimeBridgeFrameReady(
                        windowId = 1,
                        surfaceId = 1,
                        generation = 1,
                        frameId = 1,
                    ),
                )
        val event =
            machine.apply(
                RuntimeDisplayBridgeFrame(
                    RuntimeDisplayBridgeMessageType
                        .FRAME_READY,
                    2,
                    ready,
                ),
            ).getOrThrow()

        assertTrue(
            event is
                RuntimeDisplayBridgeEvent
                    .FrameReady,
        )
    }

}
