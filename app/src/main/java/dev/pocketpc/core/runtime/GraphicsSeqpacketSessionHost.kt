package dev.pocketpc.core.runtime

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host side of the private graphics-resource channel used by the Wine guest.
 *
 * The channel is deliberately separate from the display-bridge stream: Vulkan
 * resource handles require message boundaries and SCM_RIGHTS, so this endpoint
 * uses AF_UNIX/SOCK_SEQPACKET. The token is per-session and the native accept
 * path also checks SO_PEERCRED against the PID claimed by the guest handshake.
 *
 * This is transport/authentication infrastructure only. Creating a session is
 * not evidence that Wine imported an image, synchronized a GPU queue, produced
 * a visible frame, or ran Roblox.
 */
object GraphicsSeqpacketSessionHost {
    const val PROTOCOL_VERSION = 1
    const val TOKEN_BYTES = 32
    const val MIN_AUTH_TIMEOUT_MILLIS = 100L
    const val MAX_AUTH_TIMEOUT_MILLIS = 120_000L
    const val DEFAULT_AUTH_TIMEOUT_MILLIS = 8_000L
    private const val SOCKET_PREFIX = "pocketpc-gfx-"

    private val secureRandom = SecureRandom()
    private val loadResult: Result<Unit> =
        runCatching { System.loadLibrary("pocketpc_runtime") }

    private external fun nativeCreateServer(
        socketName: String,
        token: ByteArray,
    ): Long

    private external fun nativeAcceptAuthenticated(
        sessionId: Long,
        timeoutMillis: Int,
    ): Int

    private external fun nativeSendResourceOffer(
        fd: Int,
        resourceId: Long,
        generation: Long,
        width: Int,
        height: Int,
        layers: Int,
        pixelFormat: Int,
        usage: Long,
        producerPid: Int,
        processNamespace: Long,
        sequence: Long,
        ownershipState: Int,
    ): Boolean

    private external fun nativeCloseAcceptedFd(fd: Int)
    private external fun nativeCloseServer(sessionId: Long)

    val nativeHostLoaded: Boolean
        get() = loadResult.isSuccess

    data class LaunchEnvironment internal constructor(
        val socketName: String,
        val tokenHex: String,
    ) {
        val variables: Map<String, String>
            get() =
                mapOf(
                    "POCKETPC_GRAPHICS_SOCKET_NAME" to socketName,
                    "POCKETPC_GRAPHICS_SESSION_TOKEN" to tokenHex,
                    "POCKETPC_GRAPHICS_SESSION_PROTOCOL" to PROTOCOL_VERSION.toString(),
                )
    }

    class AcceptedConnection internal constructor(
        internal val fd: Int,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        val valid: Boolean
            get() = fd >= 0 && !closed.get()

        /**
         * Sends descriptor + ownership in the canonical PGT RESOURCE_OFFER
         * packet. No FD is serialized here; PVI1/PVS1 follow separately via
         * SCM_RIGHTS on this same authenticated socket.
         */
        fun sendResourceOffer(
            descriptor: GuestGraphicsResourceDescriptor,
            ownership: GuestGraphicsOwnershipToken,
        ): Boolean {
            if (!valid || !descriptor.structurallyValid || !ownership.validIdentity) return false
            if (
                descriptor.resourceId != ownership.resourceId ||
                descriptor.generation != ownership.generation ||
                descriptor.syncSequence != ownership.sequence ||
                ownership.state != GuestGraphicsOwnershipState.OFFERED_TO_GUEST
            ) {
                return false
            }

            return runCatching {
                GraphicsSeqpacketSessionHost.nativeSendResourceOffer(
                    fd = fd,
                    resourceId = descriptor.resourceId,
                    generation = descriptor.generation,
                    width = descriptor.width,
                    height = descriptor.height,
                    layers = descriptor.layers,
                    pixelFormat = descriptor.pixelFormat,
                    usage = descriptor.usage,
                    producerPid = descriptor.producerPid,
                    processNamespace = descriptor.processNamespace,
                    sequence = ownership.sequence,
                    ownershipState = ownership.state.wireValue(),
                )
            }.getOrDefault(false)
        }

        override fun close() {
            if (fd >= 0 && closed.compareAndSet(false, true)) {
                GraphicsSeqpacketSessionHost.nativeCloseAcceptedFd(fd)
            }
        }
    }

    class Session internal constructor(
        private val sessionId: Long,
        val launchEnvironment: LaunchEnvironment,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        /**
         * Blocking, but bounded in native code with poll() and a monotonic
         * deadline. Call from the runtime IO executor, never the Compose thread.
         */
        fun acceptAuthenticated(
            timeoutMillis: Long = DEFAULT_AUTH_TIMEOUT_MILLIS,
        ): AcceptedConnection? {
            if (closed.get() || sessionId <= 0L) return null
            if (timeoutMillis !in MIN_AUTH_TIMEOUT_MILLIS..MAX_AUTH_TIMEOUT_MILLIS) return null

            val fd =
                runCatching {
                    GraphicsSeqpacketSessionHost.nativeAcceptAuthenticated(
                        sessionId,
                        timeoutMillis.toInt(),
                    )
                }.getOrNull() ?: return null
            return fd.takeIf { it >= 0 }?.let(::AcceptedConnection)
        }

        override fun close() {
            if (sessionId > 0L && closed.compareAndSet(false, true)) {
                GraphicsSeqpacketSessionHost.nativeCloseServer(sessionId)
            }
        }
    }

    fun createSession(): Session? {
        if (!nativeHostLoaded) return null

        val token = ByteArray(TOKEN_BYTES).also(secureRandom::nextBytes)
        val socketSuffix = ByteArray(12).also(secureRandom::nextBytes).toHex()
        val socketName = SOCKET_PREFIX + socketSuffix
        val tokenHex = token.toHex()

        val id = runCatching { nativeCreateServer(socketName, token) }.getOrNull() ?: return null
        if (id <= 0L) return null

        return Session(
            sessionId = id,
            launchEnvironment = LaunchEnvironment(
                socketName = socketName,
                tokenHex = tokenHex,
            ),
        )
    }

    private fun GuestGraphicsOwnershipState.wireValue(): Int =
        when (this) {
            GuestGraphicsOwnershipState.HOST_AVAILABLE -> 1
            GuestGraphicsOwnershipState.OFFERED_TO_GUEST -> 2
            GuestGraphicsOwnershipState.GUEST_IMPORTED -> 3
            GuestGraphicsOwnershipState.GUEST_RENDERING -> 4
            GuestGraphicsOwnershipState.GUEST_RENDER_COMPLETE -> 5
            GuestGraphicsOwnershipState.HOST_PRESENTING -> 6
            GuestGraphicsOwnershipState.RETIRED -> 7
        }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
