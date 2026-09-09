package dev.pocketpc.core.runtime

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class RuntimeDisplayBridgeMessageType(
    val wireId: Int,
) {
    HELLO(1),
    HELLO_ACK(2),
    WINDOW_CREATE(10),
    WINDOW_GEOMETRY(11),
    WINDOW_DESTROY(12),
    SURFACE_AVAILABLE(20),
    FRAME_READY(21),
    POINTER_EVENT(30),
    KEY_EVENT(31),
    GAMEPAD_EVENT(32),
    FRAME_PRESENTED(40),
    ERROR(255);

    companion object {
        fun fromWireId(
            wireId: Int,
        ): RuntimeDisplayBridgeMessageType? =
            entries.firstOrNull {
                it.wireId == wireId
            }
    }
}

data class RuntimeDisplayBridgeFrame(
    val type:
        RuntimeDisplayBridgeMessageType,
    val sequence: Long,
    val payload: ByteArray,
)

object RuntimeDisplayBridgeProtocol {
    const val VERSION = 1
    const val HEADER_BYTES = 20
    const val MAX_PAYLOAD_BYTES =
        1024 * 1024

    private const val MAGIC =
        0x31424450 // "PDB1" little-endian bytes

    fun encode(
        frame: RuntimeDisplayBridgeFrame,
    ): ByteArray {
        require(frame.sequence >= 0L) {
            "DISPLAY_BRIDGE_SEQUENCE_INVALID"
        }
        require(
            frame.payload.size <=
                MAX_PAYLOAD_BYTES,
        ) {
            "DISPLAY_BRIDGE_PAYLOAD_TOO_LARGE"
        }

        return ByteBuffer
            .allocate(
                HEADER_BYTES +
                    frame.payload.size,
            )
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                putInt(MAGIC)
                putShort(
                    VERSION.toShort(),
                )
                putShort(
                    frame.type.wireId
                        .toShort(),
                )
                putInt(frame.payload.size)
                putLong(frame.sequence)
                put(frame.payload)
            }
            .array()
    }

    fun readFrame(
        input: InputStream,
    ): Result<RuntimeDisplayBridgeFrame> =
        runCatching {
            val header =
                ByteArray(HEADER_BYTES)
            readFully(
                input,
                header,
            )

            val headerBuffer =
                ByteBuffer.wrap(header)
                    .order(
                        ByteOrder.LITTLE_ENDIAN,
                    )
            val magic =
                headerBuffer.int
            require(magic == MAGIC) {
                "DISPLAY_BRIDGE_MAGIC_INVALID"
            }

            val version =
                headerBuffer.short
                    .toInt() and 0xffff
            require(version == VERSION) {
                "DISPLAY_BRIDGE_VERSION_UNSUPPORTED"
            }

            val typeId =
                headerBuffer.short
                    .toInt() and 0xffff
            require(
                RuntimeDisplayBridgeMessageType
                    .fromWireId(typeId) !=
                    null,
            ) {
                "DISPLAY_BRIDGE_TYPE_UNKNOWN"
            }

            val payloadBytes =
                headerBuffer.int
            require(
                payloadBytes in
                    0..MAX_PAYLOAD_BYTES,
            ) {
                "DISPLAY_BRIDGE_PAYLOAD_LENGTH_INVALID"
            }

            val sequence =
                headerBuffer.long
            require(sequence >= 0L) {
                "DISPLAY_BRIDGE_SEQUENCE_INVALID"
            }

            val payload =
                ByteArray(payloadBytes)
            readFully(
                input,
                payload,
            )

            decode(
                header + payload,
            ).getOrThrow()
        }

    fun writeFrame(
        output: OutputStream,
        frame: RuntimeDisplayBridgeFrame,
    ) {
        output.write(
            encode(frame),
        )
        output.flush()
    }

    private fun readFully(
        input: InputStream,
        destination: ByteArray,
    ) {
        var offset = 0
        while (
            offset <
            destination.size
        ) {
            val read =
                input.read(
                    destination,
                    offset,
                    destination.size -
                        offset,
                )
            if (read < 0) {
                throw EOFException(
                    "DISPLAY_BRIDGE_STREAM_TRUNCATED",
                )
            }
            if (read == 0) {
                throw EOFException(
                    "DISPLAY_BRIDGE_STREAM_NO_PROGRESS",
                )
            }
            offset += read
        }
    }

    fun decode(
        bytes: ByteArray,
    ): Result<RuntimeDisplayBridgeFrame> =
        runCatching {
            require(
                bytes.size >= HEADER_BYTES,
            ) {
                "DISPLAY_BRIDGE_FRAME_TRUNCATED"
            }

            val buffer =
                ByteBuffer.wrap(bytes)
                    .order(
                        ByteOrder.LITTLE_ENDIAN,
                    )

            require(
                buffer.int == MAGIC,
            ) {
                "DISPLAY_BRIDGE_MAGIC_INVALID"
            }

            val version =
                buffer.short
                    .toInt() and 0xffff
            require(version == VERSION) {
                "DISPLAY_BRIDGE_VERSION_UNSUPPORTED"
            }

            val typeId =
                buffer.short
                    .toInt() and 0xffff
            val type =
                RuntimeDisplayBridgeMessageType
                    .fromWireId(typeId)
                    ?: error(
                        "DISPLAY_BRIDGE_TYPE_UNKNOWN"
                    )

            val payloadBytes =
                buffer.int
            require(
                payloadBytes in
                    0..MAX_PAYLOAD_BYTES,
            ) {
                "DISPLAY_BRIDGE_PAYLOAD_LENGTH_INVALID"
            }

            val sequence =
                buffer.long
            require(sequence >= 0L) {
                "DISPLAY_BRIDGE_SEQUENCE_INVALID"
            }

            require(
                bytes.size ==
                    HEADER_BYTES +
                    payloadBytes,
            ) {
                "DISPLAY_BRIDGE_FRAME_LENGTH_MISMATCH"
            }

            val payload =
                ByteArray(payloadBytes)
            buffer.get(payload)

            RuntimeDisplayBridgeFrame(
                type = type,
                sequence = sequence,
                payload = payload,
            )
        }
}

data class RuntimeDisplayBridgeHello(
    val sessionToken: ByteArray,
    val runtimeIdentitySha256: String,
    val capabilities: Int,
) {
    init {
        require(
            sessionToken.size ==
                TOKEN_BYTES,
        ) {
            "DISPLAY_BRIDGE_TOKEN_LENGTH_INVALID"
        }
        require(
            runtimeIdentitySha256.matches(
                SHA256_REGEX,
            ),
        ) {
            "DISPLAY_BRIDGE_RUNTIME_ID_INVALID"
        }
    }

    companion object {
        const val TOKEN_BYTES = 32
        private val SHA256_REGEX =
            Regex("^[0-9a-f]{64}$")
    }
}

object RuntimeDisplayBridgeHelloCodec {
    const val PAYLOAD_BYTES =
        RuntimeDisplayBridgeHello.TOKEN_BYTES +
            64 +
            4

    fun encode(
        hello: RuntimeDisplayBridgeHello,
    ): ByteArray =
        ByteBuffer
            .allocate(PAYLOAD_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put(hello.sessionToken)
                put(
                    hello.runtimeIdentitySha256
                        .toByteArray(
                            Charsets.US_ASCII,
                        ),
                )
                putInt(hello.capabilities)
            }
            .array()

    fun decode(
        payload: ByteArray,
    ): Result<RuntimeDisplayBridgeHello> =
        runCatching {
            require(
                payload.size ==
                    PAYLOAD_BYTES,
            ) {
                "DISPLAY_BRIDGE_HELLO_SIZE_INVALID"
            }

            val buffer =
                ByteBuffer.wrap(payload)
                    .order(
                        ByteOrder.LITTLE_ENDIAN,
                    )
            val token =
                ByteArray(
                    RuntimeDisplayBridgeHello
                        .TOKEN_BYTES,
                )
            buffer.get(token)

            val identityBytes =
                ByteArray(64)
            buffer.get(identityBytes)
            val identity =
                identityBytes.toString(
                    Charsets.US_ASCII,
                )

            RuntimeDisplayBridgeHello(
                sessionToken = token,
                runtimeIdentitySha256 =
                    identity,
                capabilities =
                    buffer.int,
            )
        }
}

object RuntimeDisplayBridgeCapabilities {
    const val WINDOW_SURFACE =
        1 shl 0
    const val POINTER =
        1 shl 1
    const val KEYBOARD =
        1 shl 2
    const val GAMEPAD =
        1 shl 3
    const val FRAME_ACK =
        1 shl 4

    const val HOST_BASELINE =
        WINDOW_SURFACE or
            POINTER or
            KEYBOARD or
            FRAME_ACK
}
