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
 * A successful host offer means only that PGT RESOURCE_OFFER + PVI1 + PVS1
 * were sent over the authenticated SOCK_SEQPACKET connection. Guest import is
 * confirmed separately through PGA1 stages 1..4. PGA1 stage 5 is emitted only
 * after the pinned Wine Present callback signals PVS1 on the exact queue used
 * by host vkQueuePresentKHR. That proves same-Present-queue ordering when it is
 * observed in a real run, but not swapchain capture or a host-visible frame.
 */
class GuestGraphicsSessionOrchestrator private constructor(
    private val session: GraphicsSeqpacketSessionHost.Session,
) : AutoCloseable {
    enum class State {
        CREATED,
        AUTHENTICATED,
        RESOURCE_OFFERED,
        GUEST_IMPORT_CONFIRMED,
        PRESENT_QUEUE_SIGNAL_OBSERVED,
        CLOSED,
    }

    data class ResourceOffer(
        val resourceId: Long,
        val generation: Long,
        val width: Int,
        val height: Int,
        val descriptor: GuestGraphicsResourceDescriptor,
        val ownership: GuestGraphicsOwnershipToken,
        val pgtOfferSent: Boolean,
        val pvi1Sent: Boolean,
        val pvs1Sent: Boolean,
        val guestImportAcknowledgements:
            GraphicsSeqpacketSessionHost.ImportAcknowledgements? = null,
        val guestImportedOwnership: GuestGraphicsOwnershipToken? = null,
        val gpuQueueSignalAcknowledgement:
            GraphicsSeqpacketSessionHost.GpuQueueSignalAcknowledgement? = null,
    ) {
        val hostOfferComplete: Boolean
            get() =
                pgtOfferSent &&
                    pvi1Sent &&
                    pvs1Sent &&
                    ownership.state == GuestGraphicsOwnershipState.OFFERED_TO_GUEST

        val guestImportConfirmed: Boolean
            get() =
                hostOfferComplete &&
                    guestImportAcknowledgements?.ready == true &&
                    guestImportedOwnership?.state == GuestGraphicsOwnershipState.GUEST_IMPORTED &&
                    guestImportedOwnership.resourceId == resourceId &&
                    guestImportedOwnership.generation == generation &&
                    guestImportedOwnership.sequence == ownership.sequence + 1L

        val gpuQueueSignalObserved: Boolean
            get() =
                guestImportConfirmed &&
                    gpuQueueSignalAcknowledgement?.submitted == true &&
                    gpuQueueSignalAcknowledgement.presentQueueOrdered &&
                    !gpuQueueSignalAcknowledgement.hostVisibleFrame
    }

    data class Snapshot(
        val state: State,
        val authenticated: Boolean,
        val activeResourceId: Long?,
        val activeGeneration: Long?,
        val hostResourceOffered: Boolean,
        val guestImportConfirmed: Boolean,
        val gpuQueueSignalObserved: Boolean,
        val gpuQueueFamilyIndex: Int?,
        val presentQueueOrdered: Boolean,
        val hostVisibleFrame: Boolean,
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
        const val BLOCKER_PGT_OFFER_SEND_FAILED =
            "GUEST_GRAPHICS_PGT_OFFER_SEND_FAILED"
        const val BLOCKER_PVI1_SEND_FAILED =
            "GUEST_GRAPHICS_PVI1_SEND_FAILED"
        const val BLOCKER_PVS1_SEND_FAILED =
            "GUEST_GRAPHICS_PVS1_SEND_FAILED"
        const val BLOCKER_IMPORT_ACK_FAILED =
            "GUEST_GRAPHICS_IMPORT_ACK_FAILED"
        const val BLOCKER_IMPORT_OWNERSHIP_CONFIRM_FAILED =
            "GUEST_GRAPHICS_IMPORT_OWNERSHIP_CONFIRM_FAILED"
        const val BLOCKER_GPU_QUEUE_SIGNAL_ACK_FAILED =
            "GUEST_GRAPHICS_GPU_QUEUE_SIGNAL_ACK_FAILED"
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

    val launchEnvironment: Map<String, String>
        get() = session.launchEnvironment.variables.toMap()

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
        RuntimeGraphicsEvidenceLog.authenticated()
        return true
    }

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
                        throw IllegalStateException(BLOCKER_RESOURCE_CREATE_FAILED, it)
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

                if (!connection.sendResourceOffer(descriptor, transition.current)) {
                    lastBlocker = BLOCKER_PGT_OFFER_SEND_FAILED
                    throw IllegalStateException(BLOCKER_PGT_OFFER_SEND_FAILED)
                }

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
                        pgtOfferSent = true,
                        pvi1Sent = true,
                        pvs1Sent = true,
                    )
                check(offer.hostOfferComplete) { BLOCKER_OWNERSHIP_OFFER_FAILED }

                activeLease = lease
                activeOffer = offer
                lastBlocker = null
                keepLease = true
                RuntimeGraphicsEvidenceLog.resourceOffered(
                    resourceId = offer.resourceId,
                    generation = offer.generation,
                    width = offer.width,
                    height = offer.height,
                )
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
            RuntimeGraphicsEvidenceLog.blocked(lastBlocker ?: BLOCKER_RESOURCE_CREATE_FAILED)
        }

    fun awaitGuestImportConfirmation(
        timeoutMillis: Long = GraphicsSeqpacketSessionHost.DEFAULT_AUTH_TIMEOUT_MILLIS,
    ): Result<ResourceOffer> =
        runCatching {
            check(!closed.get()) { BLOCKER_CLOSED }
            check(authenticated.get()) { BLOCKER_NOT_AUTHENTICATED }

            val connection = acceptedConnection
            check(connection != null && connection.valid) {
                BLOCKER_NOT_AUTHENTICATED
            }

            val offer = activeOffer
                ?: throw IllegalStateException(BLOCKER_IMPORT_ACK_FAILED)
            check(offer.hostOfferComplete) { BLOCKER_IMPORT_ACK_FAILED }
            if (offer.guestImportConfirmed) return@runCatching offer

            val acknowledgements =
                connection.awaitImportAcknowledgements(
                    resourceId = offer.resourceId,
                    generation = offer.generation,
                    sequence = offer.ownership.sequence,
                    timeoutMillis = timeoutMillis,
                ) ?: run {
                    lastBlocker = BLOCKER_IMPORT_ACK_FAILED
                    throw IllegalStateException(BLOCKER_IMPORT_ACK_FAILED)
                }

            val transition =
                GuestGraphicsOwnershipProtocol.transition(
                    token = offer.ownership,
                    event = GuestGraphicsOwnershipEvent.CONFIRM_GUEST_IMPORT,
                    sequence = offer.ownership.sequence + 1L,
                )
            if (!transition.accepted ||
                transition.current.state != GuestGraphicsOwnershipState.GUEST_IMPORTED
            ) {
                lastBlocker = BLOCKER_IMPORT_OWNERSHIP_CONFIRM_FAILED
                throw IllegalStateException(
                    transition.blocker ?: BLOCKER_IMPORT_OWNERSHIP_CONFIRM_FAILED,
                )
            }

            val confirmed =
                offer.copy(
                    guestImportAcknowledgements = acknowledgements,
                    guestImportedOwnership = transition.current,
                )
            check(confirmed.guestImportConfirmed) {
                BLOCKER_IMPORT_OWNERSHIP_CONFIRM_FAILED
            }

            activeOffer = confirmed
            lastBlocker = null
            RuntimeGraphicsEvidenceLog.guestImportConfirmed(
                resourceId = confirmed.resourceId,
                generation = confirmed.generation,
            )
            confirmed
        }.onFailure {
            if (lastBlocker == null) {
                lastBlocker =
                    it.message?.takeIf(String::isNotBlank)
                        ?: BLOCKER_IMPORT_ACK_FAILED
            }
            RuntimeGraphicsEvidenceLog.blocked(lastBlocker ?: BLOCKER_IMPORT_ACK_FAILED)
        }

    /**
     * Wait for PGA1 stage 5 after import confirmation. The patched Wine v49
     * calls the driver immediately after host vkQueuePresentKHR with the exact
     * vulkan_queue used for Present. The driver submits the PVS1 signal on that
     * queue and only then sends stage 5.
     *
     * This may prove same-Present-queue ordering in an executed run, but the
     * exported PocketPC image still is not populated from the swapchain here.
     */
    fun awaitGpuQueueSignalProbe(
        timeoutMillis: Long = GraphicsSeqpacketSessionHost.DEFAULT_AUTH_TIMEOUT_MILLIS,
    ): Result<ResourceOffer> =
        runCatching {
            check(!closed.get()) { BLOCKER_CLOSED }
            check(authenticated.get()) { BLOCKER_NOT_AUTHENTICATED }

            val connection = acceptedConnection
            check(connection != null && connection.valid) {
                BLOCKER_NOT_AUTHENTICATED
            }

            val offer = activeOffer
                ?: throw IllegalStateException(BLOCKER_GPU_QUEUE_SIGNAL_ACK_FAILED)
            check(offer.guestImportConfirmed) { BLOCKER_GPU_QUEUE_SIGNAL_ACK_FAILED }
            if (offer.gpuQueueSignalObserved) return@runCatching offer

            val acknowledgement =
                connection.awaitGpuQueueSignalAcknowledgement(
                    resourceId = offer.resourceId,
                    generation = offer.generation,
                    sequence = offer.ownership.sequence,
                    timeoutMillis = timeoutMillis,
                ) ?: run {
                    lastBlocker = BLOCKER_GPU_QUEUE_SIGNAL_ACK_FAILED
                    throw IllegalStateException(BLOCKER_GPU_QUEUE_SIGNAL_ACK_FAILED)
                }

            check(
                acknowledgement.submitted &&
                    acknowledgement.presentQueueOrdered &&
                    !acknowledgement.hostVisibleFrame
            ) {
                BLOCKER_GPU_QUEUE_SIGNAL_ACK_FAILED
            }

            val updated =
                offer.copy(
                    gpuQueueSignalAcknowledgement = acknowledgement,
                )
            check(updated.gpuQueueSignalObserved) {
                BLOCKER_GPU_QUEUE_SIGNAL_ACK_FAILED
            }

            activeOffer = updated
            lastBlocker = null
            RuntimeGraphicsEvidenceLog.presentQueueSignalObserved(
                resourceId = updated.resourceId,
                generation = updated.generation,
                queueFamilyIndex = acknowledgement.queueFamilyIndex,
            )
            updated
        }.onFailure {
            if (lastBlocker == null) {
                lastBlocker =
                    it.message?.takeIf(String::isNotBlank)
                        ?: BLOCKER_GPU_QUEUE_SIGNAL_ACK_FAILED
            }
            RuntimeGraphicsEvidenceLog.blocked(lastBlocker ?: BLOCKER_GPU_QUEUE_SIGNAL_ACK_FAILED)
        }

    fun activeOffer(): ResourceOffer? = activeOffer

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
        val queueAck = offer?.gpuQueueSignalAcknowledgement
        return Snapshot(
            state =
                when {
                    isClosed -> State.CLOSED
                    offer?.gpuQueueSignalObserved == true -> State.PRESENT_QUEUE_SIGNAL_OBSERVED
                    offer?.guestImportConfirmed == true -> State.GUEST_IMPORT_CONFIRMED
                    offer?.hostOfferComplete == true -> State.RESOURCE_OFFERED
                    isAuthenticated -> State.AUTHENTICATED
                    else -> State.CREATED
                },
            authenticated = isAuthenticated,
            activeResourceId = offer?.resourceId,
            activeGeneration = offer?.generation,
            hostResourceOffered = offer?.hostOfferComplete == true,
            guestImportConfirmed = offer?.guestImportConfirmed == true,
            gpuQueueSignalObserved = offer?.gpuQueueSignalObserved == true,
            gpuQueueFamilyIndex = queueAck?.queueFamilyIndex,
            presentQueueOrdered = queueAck?.presentQueueOrdered == true,
            hostVisibleFrame = queueAck?.hostVisibleFrame == true,
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
        RuntimeGraphicsEvidenceLog.blocked(blocker)
        return false
    }
}
