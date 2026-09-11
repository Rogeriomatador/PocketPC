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
 * This is transport/authentication infrastructure only. Creating a session or
 * receiving import/queue acknowledgements is not evidence of visible
 * presentation, DXVK Present ordering, or Roblox gameplay.
 */
object GraphicsSeqpacketSessionHost {
    const val PROTOCOL_VERSION = 1
    const val TOKEN_BYTES = 32
    const val MIN_AUTH_TIMEOUT_MILLIS = 100L
    const val MAX_AUTH_TIMEOUT_MILLIS = 120_000L
    const val DEFAULT_AUTH_TIMEOUT_MILLIS = 8_000L
    const val IMPORT_ACK_READY_MASK = 0x0f
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

    private external fun nativeAwaitImportAcks(
        fd: Int,
        resourceId: Long,
        generation: Long,
        sequence: Long,
        timeoutMillis: Int,
    ): Int

    private external fun nativeAwaitGpuSignalAck(
        fd: Int,
        resourceId: Long,
        generation: Long,
        sequence: Long,
        timeoutMillis: Int,
    ): Int

    private external fun nativeCloseAcceptedFd(fd: Int)
    private external fun nativeCloseServer(sessionId: Long)

    val nativeHostLoaded: Boolean
        get() = loadResult.isSuccess

    data class ImportAcknowledgements internal constructor(
        val mask: Int,
    ) {
        val resourceOfferReceived: Boolean
            get() = mask and 0x01 != 0
        val imageImported: Boolean
            get() = mask and 0x02 != 0
        val synchronizationObjectImported: Boolean
            get() = mask and 0x04 != 0
        val ready: Boolean
            get() = mask and 0x08 != 0 && mask == IMPORT_ACK_READY_MASK
    }

    /**
     * PGA1 stage 5 proves only that the guest submitted a timeline-semaphore
     * signal through a real Wine Vulkan queue and the host received the matching
     * acknowledgement. It is deliberately not treated as Present ordering.
     */
    data class GpuQueueSignalAcknowledgement internal constructor(
        val queueFamilyIndex: Int,
    ) {
        val submitted: Boolean
            get() = queueFamilyIndex >= 0
        val presentOrdered: Boolean
            get() = false
    }

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

        /**
         * Waits for the complete PGA1 import acknowledgement chain:
         * RESOURCE_OFFER_RECEIVED -> IMAGE_IMPORTED -> SYNC_IMPORTED -> READY.
         *
         * The native implementation uses one total monotonic deadline and
         * validates exact resource identity, sequence, status and packet order.
         * READY proves only guest-side receive/import code reached that point.
         * It does not prove GPU queue synchronization or visible presentation.
         */
        fun awaitImportAcknowledgements(
            resourceId: Long,
            generation: Long,
            sequence: Long,
            timeoutMillis: Long = DEFAULT_AUTH_TIMEOUT_MILLIS,
        ): ImportAcknowledgements? {
            if (!valid) return null
            if (resourceId <= 0L || generation <= 0L || sequence <= 0L) return null
            if (timeoutMillis !in MIN_AUTH_TIMEOUT_MILLIS..MAX_AUTH_TIMEOUT_MILLIS) return null

            val mask =
                runCatching {
                    GraphicsSeqpacketSessionHost.nativeAwaitImportAcks(
                        fd = fd,
                        resourceId = resourceId,
                        generation = generation,
                        sequence = sequence,
                        timeoutMillis = timeoutMillis.toInt(),
                    )
                }.getOrNull() ?: return null

            return mask.takeIf { it == IMPORT_ACK_READY_MASK }
                ?.let(::ImportAcknowledgements)
                ?.takeIf { it.ready }
        }

        /**
         * Waits only for PGA1 GPU_SIGNAL_SUBMITTED (stage 5). Import readiness
         * remains stages 1..4 and is never promoted by this diagnostic ACK.
         */
        fun awaitGpuQueueSignalAcknowledgement(
            resourceId: Long,
            generation: Long,
            sequence: Long,
            timeoutMillis: Long = DEFAULT_AUTH_TIMEOUT_MILLIS,
        ): GpuQueueSignalAcknowledgement? {
            if (!valid) return null
            if (resourceId <= 0L || generation <= 0L || sequence <= 0L) return null
            if (timeoutMillis !in MIN_AUTH_TIMEOUT_MILLIS..MAX_AUTH_TIMEOUT_MILLIS) return null

            val queueFamilyIndex =
                runCatching {
                    GraphicsSeqpacketSessionHost.nativeAwaitGpuSignalAck(
                        fd = fd,
                        resourceId = resourceId,
                        generation = generation,
                        sequence = sequence,
                        timeoutMillis = timeoutMillis.toInt(),
                    )
                }.getOrNull() ?: return null

            return queueFamilyIndex.takeIf { it >= 0 }
                ?.let(::GpuQueueSignalAcknowledgement)
                ?.takeIf { it.submitted && !it.presentOrdered }
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
