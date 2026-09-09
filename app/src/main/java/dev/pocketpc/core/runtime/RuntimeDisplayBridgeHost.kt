package dev.pocketpc.core.runtime

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

interface RuntimeDisplayBridgeEndpoint {
    val negotiatedCapabilities: Int

    fun readFrame():
        Result<RuntimeDisplayBridgeFrame>

    fun sendSurfaceAvailable(
        surface:
            RuntimeBridgeSurfaceAvailable,
    )

    fun sendPointer(
        event: RuntimeBridgePointerEvent,
    )

    fun sendKey(
        event: RuntimeBridgeKeyEvent,
    )

    fun sendFramePresented(
        event:
            RuntimeBridgeFramePresented,
    )
}

class RuntimeDisplayBridgePeer internal constructor(
    private val socket: LocalSocket,
    override val negotiatedCapabilities: Int,
) : RuntimeDisplayBridgeEndpoint, Closeable {
    private val terminal =
        AtomicBoolean(false)
    private val readLock = Any()
    private val writeLock = Any()

    private var outboundSequence = 1L
    private var expectedInboundSequence = 1L

    override fun readFrame():
        Result<RuntimeDisplayBridgeFrame> =
        synchronized(readLock) {
            if (terminal.get()) {
                return@synchronized Result.failure(
                    IllegalStateException(
                        "DISPLAY_BRIDGE_PEER_TERMINAL",
                    ),
                )
            }

            val decoded =
                RuntimeDisplayBridgeProtocol
                    .readFrame(
                        socket.inputStream,
                    )

            if (decoded.isFailure) {
                invalidate()
                return@synchronized decoded
            }

            val frame =
                decoded.getOrThrow()
            val expected =
                expectedInboundSequence

            if (
                expected <= 0L ||
                frame.sequence != expected
            ) {
                invalidate()
                return@synchronized Result.failure(
                    IllegalStateException(
                        if (expected <= 0L) {
                            "DISPLAY_BRIDGE_SEQUENCE_EXHAUSTED"
                        } else {
                            "DISPLAY_BRIDGE_SEQUENCE_INVALID:" +
                                "expected=" +
                                expected +
                                ":actual=" +
                                frame.sequence
                        },
                    ),
                )
            }

            expectedInboundSequence =
                if (
                    expected ==
                    Long.MAX_VALUE
                ) {
                    0L
                } else {
                    expected + 1L
                }

            Result.success(frame)
        }

    internal fun writeFrame(
        frame: RuntimeDisplayBridgeFrame,
    ) {
        synchronized(writeLock) {
            check(!terminal.get()) {
                "DISPLAY_BRIDGE_PEER_TERMINAL"
            }

            val expected =
                outboundSequence
            require(expected > 0L) {
                invalidate()
                "DISPLAY_BRIDGE_SEQUENCE_EXHAUSTED"
            }
            require(
                frame.sequence ==
                    expected,
            ) {
                invalidate()
                "DISPLAY_BRIDGE_OUTBOUND_SEQUENCE_INVALID"
            }

            try {
                RuntimeDisplayBridgeProtocol
                    .writeFrame(
                        socket.outputStream,
                        frame,
                    )
            } catch (
                error: Throwable
            ) {
                invalidate()
                throw error
            }

            outboundSequence =
                if (
                    expected ==
                    Long.MAX_VALUE
                ) {
                    0L
                } else {
                    expected + 1L
                }
        }
    }

    override fun sendSurfaceAvailable(
        surface:
            RuntimeBridgeSurfaceAvailable,
    ) = send(
        RuntimeDisplayBridgeMessageType
            .SURFACE_AVAILABLE,
        RuntimeDisplayBridgeCapabilities
            .WINDOW_SURFACE,
        RuntimeDisplayBridgePayloadCodec
            .encodeSurfaceAvailable(
                surface,
            ),
    )

    override fun sendPointer(
        event: RuntimeBridgePointerEvent,
    ) = send(
        RuntimeDisplayBridgeMessageType
            .POINTER_EVENT,
        RuntimeDisplayBridgeCapabilities
            .POINTER,
        RuntimeDisplayBridgePayloadCodec
            .encodePointerEvent(event),
    )

    override fun sendKey(
        event: RuntimeBridgeKeyEvent,
    ) = send(
        RuntimeDisplayBridgeMessageType
            .KEY_EVENT,
        RuntimeDisplayBridgeCapabilities
            .KEYBOARD,
        RuntimeDisplayBridgePayloadCodec
            .encodeKeyEvent(event),
    )

    override fun sendFramePresented(
        event:
            RuntimeBridgeFramePresented,
    ) = send(
        RuntimeDisplayBridgeMessageType
            .FRAME_PRESENTED,
        RuntimeDisplayBridgeCapabilities
            .FRAME_ACK,
        RuntimeDisplayBridgePayloadCodec
            .encodeFramePresented(
                event,
            ),
    )

    private fun send(
        type:
            RuntimeDisplayBridgeMessageType,
        capability: Int,
        payload: ByteArray,
    ) {
        synchronized(writeLock) {
            check(!terminal.get()) {
                "DISPLAY_BRIDGE_PEER_TERMINAL"
            }
            require(
                negotiatedCapabilities and
                    capability != 0,
            ) {
                "DISPLAY_BRIDGE_CAPABILITY_NOT_NEGOTIATED"
            }

            val sequence =
                outboundSequence
            require(sequence > 0L) {
                invalidate()
                "DISPLAY_BRIDGE_SEQUENCE_EXHAUSTED"
            }

            try {
                RuntimeDisplayBridgeProtocol
                    .writeFrame(
                        socket.outputStream,
                        RuntimeDisplayBridgeFrame(
                            type = type,
                            sequence = sequence,
                            payload = payload,
                        ),
                    )
            } catch (
                error: Throwable
            ) {
                invalidate()
                throw error
            }

            outboundSequence =
                if (
                    sequence ==
                    Long.MAX_VALUE
                ) {
                    0L
                } else {
                    sequence + 1L
                }
        }
    }

    private fun invalidate() {
        if (
            terminal.compareAndSet(
                false,
                true,
            )
        ) {
            runCatching {
                socket.close()
            }
        }
    }

    override fun close() {
        invalidate()
    }
}

class RuntimeDisplayBridgeHost(
    private val session:
        RuntimeDisplayBridgeSession,
) : Closeable {
    private val closed =
        AtomicBoolean(false)
    private val server =
        LocalServerSocket(
            session.socketName,
        )

    fun acceptAuthenticated(
        readTimeoutMillis: Int =
            DEFAULT_READ_TIMEOUT_MS,
    ): Result<RuntimeDisplayBridgePeer> =
        runCatching {
            check(!closed.get()) {
                "DISPLAY_BRIDGE_HOST_CLOSED"
            }

            val socket =
                server.accept()
            try {
                socket.soTimeout =
                    if (
                        readTimeoutMillis <= 0
                    ) {
                        0
                    } else {
                        readTimeoutMillis
                            .coerceIn(
                                1_000,
                                30_000,
                            )
                    }

                val helloFrame =
                    RuntimeDisplayBridgeProtocol
                        .readFrame(
                            socket.inputStream,
                        )
                        .getOrThrow()

                val authentication =
                    RuntimeDisplayBridgeAuthenticator
                        .authenticate(
                            helloFrame,
                            session,
                        )

                require(
                    authentication.accepted,
                ) {
                    "DISPLAY_BRIDGE_AUTH_FAILED:" +
                        authentication
                            .blockers
                            .joinToString(",")
                }

                val ackPayload =
                    ByteBuffer
                        .allocate(4)
                        .order(
                            ByteOrder
                                .LITTLE_ENDIAN,
                        )
                        .putInt(
                            authentication
                                .negotiatedCapabilities,
                        )
                        .array()

                RuntimeDisplayBridgeProtocol
                    .writeFrame(
                        socket.outputStream,
                        RuntimeDisplayBridgeFrame(
                            type =
                                RuntimeDisplayBridgeMessageType
                                    .HELLO_ACK,
                            sequence = 0,
                            payload =
                                ackPayload,
                        ),
                    )

                RuntimeDisplayBridgePeer(
                    socket,
                    authentication
                        .negotiatedCapabilities,
                )
            } catch (
                error: Throwable
            ) {
                runCatching {
                    socket.close()
                }
                throw error
            }
        }

    override fun close() {
        if (
            closed.compareAndSet(
                false,
                true,
            )
        ) {
            runCatching {
                server.close()
            }
        }
    }

    companion object {
        private const val
            DEFAULT_READ_TIMEOUT_MS =
            5_000
    }
}
