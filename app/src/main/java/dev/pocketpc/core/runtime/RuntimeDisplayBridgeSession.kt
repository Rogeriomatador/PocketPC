package dev.pocketpc.core.runtime

import java.security.MessageDigest
import java.security.SecureRandom

data class RuntimeDisplayBridgeSession(
    val socketName: String,
    val sessionToken: ByteArray,
    val runtimeIdentitySha256: String,
    val hostCapabilities: Int,
) {
    fun environment(): Map<String, String> =
        mapOf(
            "POCKETPC_DISPLAY_PROTOCOL" to
                RuntimeDisplayBridgeProtocol
                    .VERSION
                    .toString(),
            "POCKETPC_DISPLAY_SOCKET" to
                socketName,
            "POCKETPC_DISPLAY_TOKEN" to
                sessionToken.toHex(),
            "POCKETPC_DISPLAY_RUNTIME_SHA256" to
                runtimeIdentitySha256,
            "POCKETPC_DISPLAY_HOST_CAPS" to
                hostCapabilities.toString(),
        )
}

object RuntimeDisplayBridgeSessionFactory {
    private val random =
        SecureRandom()

    fun create(
        runtimeIdentitySha256: String,
        hostCapabilities: Int =
            RuntimeDisplayBridgeCapabilities
                .HOST_BASELINE,
    ): RuntimeDisplayBridgeSession {
        require(
            runtimeIdentitySha256.matches(
                Regex("^[0-9a-f]{64}$"),
            ),
        ) {
            "DISPLAY_BRIDGE_RUNTIME_ID_INVALID"
        }

        val token =
            ByteArray(
                RuntimeDisplayBridgeHello
                    .TOKEN_BYTES,
            ).also(
                random::nextBytes,
            )
        val socketEntropy =
            ByteArray(12)
                .also(
                    random::nextBytes,
                )
                .toHex()

        return RuntimeDisplayBridgeSession(
            socketName =
                "pocketpc.display." +
                    socketEntropy,
            sessionToken = token,
            runtimeIdentitySha256 =
                runtimeIdentitySha256,
            hostCapabilities =
                hostCapabilities,
        )
    }
}

data class RuntimeDisplayBridgeAuthentication(
    val accepted: Boolean,
    val negotiatedCapabilities: Int,
    val blockers: List<String>,
)

object RuntimeDisplayBridgeAuthenticator {
    fun authenticate(
        frame: RuntimeDisplayBridgeFrame,
        session: RuntimeDisplayBridgeSession,
    ): RuntimeDisplayBridgeAuthentication {
        val blockers =
            mutableListOf<String>()

        if (
            frame.type !=
            RuntimeDisplayBridgeMessageType
                .HELLO
        ) {
            blockers +=
                "DISPLAY_BRIDGE_HELLO_REQUIRED"
        }
        if (frame.sequence != 0L) {
            blockers +=
                "DISPLAY_BRIDGE_HELLO_SEQUENCE_INVALID"
        }

        val hello =
            RuntimeDisplayBridgeHelloCodec
                .decode(frame.payload)
                .getOrElse {
                    blockers +=
                        (
                            it.message
                                ?: "DISPLAY_BRIDGE_HELLO_INVALID"
                            )
                    null
                }

        if (hello != null) {
            if (
                !MessageDigest.isEqual(
                    hello.sessionToken,
                    session.sessionToken,
                )
            ) {
                blockers +=
                    "DISPLAY_BRIDGE_TOKEN_MISMATCH"
            }
            if (
                hello.runtimeIdentitySha256 !=
                session.runtimeIdentitySha256
            ) {
                blockers +=
                    "DISPLAY_BRIDGE_RUNTIME_ID_MISMATCH"
            }
        }

        val candidateNegotiated =
            if (hello != null) {
                hello.capabilities and
                    session.hostCapabilities
            } else {
                0
            }

        if (
            blockers.isEmpty() &&
            candidateNegotiated and
                RuntimeDisplayBridgeCapabilities
                    .HOST_BASELINE !=
                RuntimeDisplayBridgeCapabilities
                    .HOST_BASELINE
        ) {
            blockers +=
                "DISPLAY_BRIDGE_BASELINE_CAPABILITIES_MISSING"
        }

        val negotiated =
            if (blockers.isEmpty()) {
                candidateNegotiated
            } else {
                0
            }

        return RuntimeDisplayBridgeAuthentication(
            accepted =
                blockers.isEmpty(),
            negotiatedCapabilities =
                negotiated,
            blockers =
                blockers.distinct(),
        )
    }
}
