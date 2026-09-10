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
    private const val SOCKET_PREFIX = "pocketpc-gfx-"

    private val secureRandom = SecureRandom()
    private val loadResult: Result<Unit> =
        runCatching { System.loadLibrary("pocketpc_runtime") }

    private external fun nativeCreateServer(
        socketName: String,
        token: ByteArray,
    ): Long

    private external fun nativeAcceptAuthenticated(sessionId: Long): Int
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

        override fun close() {
            if (fd >= 0 && closed.compareAndSet(false, true)) {
                nativeCloseAcceptedFd(fd)
            }
        }
    }

    class Session internal constructor(
        private val sessionId: Long,
        val launchEnvironment: LaunchEnvironment,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        /** Blocking. Call from the runtime IO executor, never the Compose thread. */
        fun acceptAuthenticated(): AcceptedConnection? {
            if (closed.get() || sessionId <= 0L) return null
            val fd = runCatching { nativeAcceptAuthenticated(sessionId) }.getOrNull() ?: return null
            return fd.takeIf { it >= 0 }?.let(::AcceptedConnection)
        }

        override fun close() {
            if (sessionId > 0L && closed.compareAndSet(false, true)) {
                nativeCloseServer(sessionId)
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

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
