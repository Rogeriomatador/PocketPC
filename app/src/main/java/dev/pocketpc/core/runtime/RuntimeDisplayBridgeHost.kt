package dev.pocketpc.core.runtime

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class RuntimeDisplayBridgePeer internal constructor(
    private val socket: LocalSocket,
    val negotiatedCapabilities: Int,
) : Closeable {
    fun readFrame():
        Result<RuntimeDisplayBridgeFrame> =
        RuntimeDisplayBridgeProtocol
            .readFrame(
                socket.inputStream,
            )

    fun writeFrame(
        frame: RuntimeDisplayBridgeFrame,
    ) {
        RuntimeDisplayBridgeProtocol
            .writeFrame(
                socket.outputStream,
                frame,
            )
    }

    override fun close() {
        runCatching {
            socket.close()
        }
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
                    readTimeoutMillis
                        .coerceIn(
                            1_000,
                            30_000,
                        )

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
                        authentication.blockers
                            .joinToString(",")
                }

                val ackPayload =
                    ByteBuffer
                        .allocate(4)
                        .order(
                            ByteOrder.LITTLE_ENDIAN,
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
                    socket = socket,
                    negotiatedCapabilities =
                        authentication
                            .negotiatedCapabilities,
                )
            } catch (error: Throwable) {
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
        private const val DEFAULT_READ_TIMEOUT_MS =
            5_000
    }
}
