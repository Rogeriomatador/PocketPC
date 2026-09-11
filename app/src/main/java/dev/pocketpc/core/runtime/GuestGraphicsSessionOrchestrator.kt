package dev.pocketpc.core.runtime

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the host-side lifetime of one authenticated guest graphics session.
 *
 * The caller must add [launchEnvironment] to the exact PRoot/Box64/Wine
 * process environment before starting the guest, then call
 * [acceptAuthenticated] from an IO thread. No Vulkan handle is offered until
 * PGH1 authentication (session token + SO_PEERCRED PID) succeeds.
 *
 * A successful host offer means only that PVI1/PVS1 were sent over the
 * authenticated SOCK_SEQPACKET connection. It is not proof that Wine/DXVK
 * imported them, submitted GPU work, presented a visible frame, or ran Roblox.
 */
class GuestGraphicsSessionOrchestrator private constructor(
    private val session: GraphicsSeqpacketSessionHost.Session,
) : AutoCloseable {
    enum class State {
        CREATED,
        AUTHENTICATED,
        RESOURCE_OFFERED,
        CLOSED,
    }

    data class ResourceOffer(
        val resourceId: Long,
        val generation: Long,
        val width: Int,
        val height: Int,
        val descriptor: GuestGraphicsResourceDescriptor,
        val ownership: GuestGraphicsOwnershipToken,
        val pvi1Sent: Boolean,
        val pvs1Sent: Boolean,
    ) {
        val hostOfferComplete: Boolean
            get() =
                pvi1Sent &&
                    pvs1Sent &&
                    ownership.state == GuestGraphicsOwnershipState.OFFERED_TO_GUEST
    }

    data class Snapshot(
        val state: State,
        val authenticated: Boolean,
        val activeResourceId: Long?,
        val activeGeneration: Long?,
        val blocker: String?,
    )

    companion object {
        const val BLOCKER_SESSION_CREATE_FAILED =
            "GUEST_GRAPHICS_SESSION_CREATE_FAILED"
        const val BLOCKER_NOT_AUTHENTICATED =
            "GUEST_GRAPHICS_SESSION_NOT_AUTHENTICATED"
        const val BLOCKER_ALREADY_AUTHENTICATED =
            "GUEST_GRAPHICS_SESSION_ALREADY_AUTHENTICATED"
        const val BLOCKER_RESOURCE_ALREADY_OFFERED =
            "GUEST_GRAPHICS_RESOURCE_ALREADY_OFFERED"
        const val BLOCKER_RESOURCE_CREATE_FAILED =
            "GUEST_GRAPHICS_RESOURCE_CREATE_FAILED"
        const val BLOCKER_OWNERSHIP_OFFER_FAILED =
            "GUEST_GRAPHICS_OWNERSHIP_OFFER_FAILED"
        const val BLOCKER_PVI1_SEND_FAILED =
            "GUEST_GRAPHICS_PVI1_SEND_FAILED"
        const val BLOCKER_PVS1_SEND_FAILED =
            "GUEST_GRAPHICS_PVS1_SEND_FAILED"
        const val BLOCKER_CLOSED =
            "GUEST_GRAPHICS_SESSION_CLOSED"

        fun create(): GuestGraphicsSessionOrchestrator? =
            GraphicsSeqpacketSessionHost.createSession()
                ?.let(::GuestGraphicsSessionOrchestrator)
    }

    private val closed = AtomicBoolean(false)
    private val authenticated = AtomicBoolean(false)

    @Volatile
    private var acceptedConnection: GraphicsSeqpacketSessionHost.AcceptedConnection? = null

    @Volatile
    private var activeLease: VulkanExternalImageFdLease? = null

    @Volatile
    private var activeOffer: ResourceOffer? = null

    @Volatile
    private var lastBlocker: String? = null

    /** Environment that must be injected into the exact guest process. */
    val launchEnvironment: Map<String, String>
        get() = session.launchEnvironment.variables.toMap()

    /**
     * Blocking but bounded in native code. Must run on the runtime IO executor.
     * Only one authenticated peer is allowed for an orchestrator instance.
     */
    fun acceptAuthenticated(
        timeoutMillis: Long = GraphicsSeqpacketSessionHost.DEFAULT_AUTH_TIMEOUT_MILLIS,
    ): Boolean {
        if (closed.get()) return fail(BLOCKER_CLOSED)
        if (!authenticated.compareAndSet(false, true)) {
            return fail(BLOCKER_ALREADY_AUTHENTICATED)
        }

        val connection = session.acceptAuthenticated(timeoutMillis)
        if (connection == null || !connection.valid) {
            authenticated.set(false)
            connection?.close()
            return fail(BLOCKER_NOT_AUTHENTICATED)
        }

        acceptedConnection = connection
        lastBlocker = null
        return true
    }

    /**
     * Creates one host OPAQUE_FD image and offers its image FD (PVI1) and
     * timeline semaphore FD (PVS1) over the already authenticated connection.
     *
     * The initial logical ownership sequence is always 1 for a new resource,
     * matching HOST_AVAILABLE -> OFFERED_TO_GUEST. Later ownership transitions
     * require guest acknowledgements and are intentionally not invented here.
     */
    fun createAndOfferExternalImage(
        width: Int,
        height: Int,
        producerPid: Int = android.os.Process.myPid(),
        processNamespace: Long = android.os.Process.myUid().toLong(),
    ): Result<ResourceOffer> =
        runCatching {
            check(!closed.get()) { BLOCKER_CLOSED }
            check(authenticated.get()) { BLOCKER_NOT_AUTHENTICATED }
            check(activeLease == null && activeOffer == null) {
                BLOCKER_RESOURCE_ALREADY_OFFERED
            }

            val connection = acceptedConnection
            check(connection != null && connection.valid) {
                BLOCKER_NOT_AUTHENTICATED
            }

            val lease =
                VulkanExternalImageFdBroker.create(width, height)
                    .getOrElse {
                        lastBlocker = BLOCKER_RESOURCE_CREATE_FAILED
                        throw IllegalStateException(
                            BLOCKER_RESOURCE_CREATE_FAILED,
                            it,
                        )
                    }

            var keepLease = false
            try {
                val initial =
                    GuestGraphicsOwnershipProtocol.initial(
                        lease.resourceId,
                        lease.generation,
                    ) ?: error(BLOCKER_OWNERSHIP_OFFER_FAILED)

                val transition =
                    GuestGraphicsOwnershipProtocol.transition(
                        token = initial,
                        event = GuestGraphicsOwnershipEvent.OFFER_TO_GUEST,
                        sequence = 1L,
                    )
                check(transition.accepted) {
                    transition.blocker ?: BLOCKER_OWNERSHIP_OFFER_FAILED
                }

                val descriptor =
                    lease.toGuestDescriptor(
                        producerPid = producerPid,
                        processNamespace = processNamespace,
                        syncSequence = transition.current.sequence,
                    )

                VulkanExternalImageFdBroker.send(
                    lease = lease,
                    socketFd = connection.fd,
                    sequence = transition.current.sequence,
                ).getOrElse {
                    lastBlocker = BLOCKER_PVI1_SEND_FAILED
                    throw IllegalStateException(BLOCKER_PVI1_SEND_FAILED, it)
                }

                VulkanExternalImageFdBroker.sendTimeline(
                    lease = lease,
                    socketFd = connection.fd,
                ).getOrElse {
                    lastBlocker = BLOCKER_PVS1_SEND_FAILED
                    throw IllegalStateException(BLOCKER_PVS1_SEND_FAILED, it)
                }

                val offer =
                    ResourceOffer(
                        resourceId = lease.resourceId,
                        generation = lease.generation,
                        width = lease.width,
                        height = lease.height,
                        descriptor = descriptor,
                        ownership = transition.current,
                        pvi1Sent = true,
                        pvs1Sent = true,
                    )
                check(offer.hostOfferComplete) {
                    BLOCKER_OWNERSHIP_OFFER_FAILED
                }

                activeLease = lease
                activeOffer = offer
                lastBlocker = null
                keepLease = true
                offer
            } finally {
                if (!keepLease) {
                    VulkanExternalImageFdBroker.release(lease)
                }
            }
        }.onFailure {
            if (lastBlocker == null) {
                lastBlocker =
                    it.message?.takeIf(String::isNotBlank)
                        ?: BLOCKER_RESOURCE_CREATE_FAILED
            }
        }

    fun activeOffer(): ResourceOffer? = activeOffer

    /**
     * Host-side retirement only. This does not claim guest/GPU completion.
     * It is primarily used during teardown or a failed integration attempt.
     */
    fun releaseActiveResource(): Boolean {
        val lease = activeLease ?: return true
        val released = VulkanExternalImageFdBroker.release(lease).isSuccess
        if (released) {
            activeLease = null
            activeOffer = null
        }
        return released
    }

    fun snapshot(): Snapshot {
        val isClosed = closed.get()
        val isAuthenticated =
            !isClosed && authenticated.get() && acceptedConnection?.valid == true
        val offer = activeOffer
        return Snapshot(
            state =
                when {
                    isClosed -> State.CLOSED
                    offer?.hostOfferComplete == true -> State.RESOURCE_OFFERED
                    isAuthenticated -> State.AUTHENTICATED
                    else -> State.CREATED
                },
            authenticated = isAuthenticated,
            activeResourceId = offer?.resourceId,
            activeGeneration = offer?.generation,
            blocker = lastBlocker,
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        releaseActiveResource()
        acceptedConnection?.close()
        acceptedConnection = null
        authenticated.set(false)
        session.close()
    }

    private fun fail(blocker: String): Boolean {
        lastBlocker = blocker
        return false
    }
}
