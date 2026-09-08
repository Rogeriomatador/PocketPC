package dev.pocketpc.core.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDisplayBridgeProtocolTest {
    @Test
    fun frameRoundTripPreservesTypeSequenceAndPayload() {
        val frame =
            RuntimeDisplayBridgeFrame(
                type =
                    RuntimeDisplayBridgeMessageType
                        .KEY_EVENT,
                sequence = 7,
                payload =
                    byteArrayOf(
                        1,
                        2,
                        3,
                    ),
            )

        val decoded =
            RuntimeDisplayBridgeProtocol
                .decode(
                    RuntimeDisplayBridgeProtocol
                        .encode(frame),
                )
                .getOrThrow()

        assertEquals(
            frame.type,
            decoded.type,
        )
        assertEquals(
            frame.sequence,
            decoded.sequence,
        )
        assertArrayEquals(
            frame.payload,
            decoded.payload,
        )
    }

    @Test
    fun malformedFrameLengthFailsClosed() {
        val frame =
            RuntimeDisplayBridgeProtocol
                .encode(
                    RuntimeDisplayBridgeFrame(
                        type =
                            RuntimeDisplayBridgeMessageType
                                .HELLO,
                        sequence = 0,
                        payload =
                            byteArrayOf(1),
                    ),
                )

        val truncated =
            frame.copyOf(
                frame.size - 1,
            )

        assertTrue(
            RuntimeDisplayBridgeProtocol
                .decode(truncated)
                .isFailure,
        )
    }

    @Test
    fun exactTokenAndRuntimeIdentityAuthenticate() {
        val token =
            ByteArray(
                RuntimeDisplayBridgeHello
                    .TOKEN_BYTES,
            ) {
                it.toByte()
            }
        val identity =
            "a".repeat(64)
        val session =
            RuntimeDisplayBridgeSession(
                socketName =
                    "pocketpc.display.test",
                sessionToken = token,
                runtimeIdentitySha256 =
                    identity,
                hostCapabilities =
                    RuntimeDisplayBridgeCapabilities
                        .HOST_BASELINE,
            )
        val hello =
            RuntimeDisplayBridgeHello(
                sessionToken = token,
                runtimeIdentitySha256 =
                    identity,
                capabilities =
                    RuntimeDisplayBridgeCapabilities
                        .HOST_BASELINE,
            )
        val frame =
            RuntimeDisplayBridgeFrame(
                type =
                    RuntimeDisplayBridgeMessageType
                        .HELLO,
                sequence = 0,
                payload =
                    RuntimeDisplayBridgeHelloCodec
                        .encode(hello),
            )

        val result =
            RuntimeDisplayBridgeAuthenticator
                .authenticate(
                    frame,
                    session,
                )

        assertTrue(
            result.blockers.joinToString(),
            result.accepted,
        )
        assertTrue(
            result.negotiatedCapabilities and
                RuntimeDisplayBridgeCapabilities
                    .WINDOW_SURFACE !=
                0,
        )
    }

    @Test
    fun wrongTokenFailsClosed() {
        val expected =
            ByteArray(
                RuntimeDisplayBridgeHello
                    .TOKEN_BYTES,
            ) {
                1
            }
        val supplied =
            expected.clone().also {
                it[0] = 2
            }
        val session =
            RuntimeDisplayBridgeSession(
                socketName =
                    "pocketpc.display.test",
                sessionToken = expected,
                runtimeIdentitySha256 =
                    "b".repeat(64),
                hostCapabilities =
                    RuntimeDisplayBridgeCapabilities
                        .HOST_BASELINE,
            )
        val frame =
            RuntimeDisplayBridgeFrame(
                type =
                    RuntimeDisplayBridgeMessageType
                        .HELLO,
                sequence = 0,
                payload =
                    RuntimeDisplayBridgeHelloCodec
                        .encode(
                            RuntimeDisplayBridgeHello(
                                sessionToken =
                                    supplied,
                                runtimeIdentitySha256 =
                                    "b".repeat(64),
                                capabilities =
                                    RuntimeDisplayBridgeCapabilities
                                        .HOST_BASELINE,
                            ),
                        ),
            )

        val result =
            RuntimeDisplayBridgeAuthenticator
                .authenticate(
                    frame,
                    session,
                )

        assertFalse(result.accepted)
        assertTrue(
            result.blockers.contains(
                "DISPLAY_BRIDGE_TOKEN_MISMATCH",
            ),
        )
    }

    @Test
    fun sessionEnvironmentDoesNotExposeBinaryToken() {
        val session =
            RuntimeDisplayBridgeSessionFactory
                .create(
                    runtimeIdentitySha256 =
                        "c".repeat(64),
                )
        val env =
            session.environment()

        assertEquals(
            64,
            requireNotNull(
                env[
                    "POCKETPC_DISPLAY_TOKEN"
                ],
            ).length,
        )
        assertEquals(
            "c".repeat(64),
            env[
                "POCKETPC_DISPLAY_RUNTIME_SHA256"
            ],
        )
    }
}
