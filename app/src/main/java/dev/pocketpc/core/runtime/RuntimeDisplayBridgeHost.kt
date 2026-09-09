package dev.pocketpc.core.runtime

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class RuntimeDisplayBridgePeer internal constructor(
    private val socket: LocalSocket,
    val negotiatedCapabilities: Int,
) : Closeable {
    private val outboundSequence = AtomicLong(1L)

    fun readFrame(): Result<RuntimeDisplayBridgeFrame> =
        RuntimeDisplayBridgeProtocol.readFrame(socket.inputStream)

    @Synchronized
    fun writeFrame(frame: RuntimeDisplayBridgeFrame) {
        RuntimeDisplayBridgeProtocol.writeFrame(socket.outputStream, frame)
    }

    fun sendSurfaceAvailable(
        surface: RuntimeBridgeSurfaceAvailable,
    ) = send(
        RuntimeDisplayBridgeMessageType.SURFACE_AVAILABLE,
        RuntimeDisplayBridgeCapabilities.WINDOW_SURFACE,
        RuntimeDisplayBridgePayloadCodec.encodeSurfaceAvailable(surface),
    )

    fun sendPointer(event: RuntimeBridgePointerEvent) = send(
        RuntimeDisplayBridgeMessageType.POINTER_EVENT,
        RuntimeDisplayBridgeCapabilities.POINTER,
        RuntimeDisplayBridgePayloadCodec.encodePointerEvent(event),
    )

    fun sendKey(event: RuntimeBridgeKeyEvent) = send(
        RuntimeDisplayBridgeMessageType.KEY_EVENT,
        RuntimeDisplayBridgeCapabilities.KEYBOARD,
        RuntimeDisplayBridgePayloadCodec.encodeKeyEvent(event),
    )

    fun sendFramePresented(event: RuntimeBridgeFramePresented) = send(
        RuntimeDisplayBridgeMessageType.FRAME_PRESENTED,
        RuntimeDisplayBridgeCapabilities.FRAME_ACK,
        RuntimeDisplayBridgePayloadCodec.encodeFramePresented(event),
    )

    @Synchronized
    private fun send(type: RuntimeDisplayBridgeMessageType, capability: Int, payload: ByteArray) {
        require(negotiatedCapabilities and capability != 0) {
            "DISPLAY_BRIDGE_CAPABILITY_NOT_NEGOTIATED"
        }
        val sequence = outboundSequence.getAndIncrement()
        require(sequence > 0L) { "DISPLAY_BRIDGE_SEQUENCE_EXHAUSTED" }
        RuntimeDisplayBridgeProtocol.writeFrame(
            socket.outputStream,
            RuntimeDisplayBridgeFrame(type = type, sequence = sequence, payload = payload),
        )
    }

    override fun close() { runCatching { socket.close() } }
}

class RuntimeDisplayBridgeHost(private val session: RuntimeDisplayBridgeSession) : Closeable {
    private val closed = AtomicBoolean(false)
    private val server = LocalServerSocket(session.socketName)

    fun acceptAuthenticated(readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MS): Result<RuntimeDisplayBridgePeer> = runCatching {
        check(!closed.get()) { "DISPLAY_BRIDGE_HOST_CLOSED" }
        val socket = server.accept()
        try {
            socket.soTimeout = readTimeoutMillis.coerceIn(1_000, 30_000)
            val helloFrame = RuntimeDisplayBridgeProtocol.readFrame(socket.inputStream).getOrThrow()
            val authentication = RuntimeDisplayBridgeAuthenticator.authenticate(helloFrame, session)
            require(authentication.accepted) {
                "DISPLAY_BRIDGE_AUTH_FAILED:" + authentication.blockers.joinToString(",")
            }
            val ackPayload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(authentication.negotiatedCapabilities).array()
            RuntimeDisplayBridgeProtocol.writeFrame(
                socket.outputStream,
                RuntimeDisplayBridgeFrame(
                    type = RuntimeDisplayBridgeMessageType.HELLO_ACK,
                    sequence = 0,
                    payload = ackPayload,
                ),
            )
            RuntimeDisplayBridgePeer(socket, authentication.negotiatedCapabilities)
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { server.close() }
    }

    companion object { private const val DEFAULT_READ_TIMEOUT_MS = 5_000 }
}
