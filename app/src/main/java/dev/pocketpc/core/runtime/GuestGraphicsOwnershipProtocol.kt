package dev.pocketpc.core.runtime

/**
 * Logical ownership protocol for a graphics resource shared between host and
 * guest. This is deliberately independent of a concrete Vulkan semaphore,
 * sync-fd or AHardwareBuffer transport implementation.
 *
 * A successful logical transition is not proof that GPU synchronization has
 * happened. It only establishes the state machine that a future transport and
 * synchronization backend must obey.
 */
enum class GuestGraphicsOwnershipState {
    HOST_AVAILABLE,
    OFFERED_TO_GUEST,
    GUEST_IMPORTED,
    GUEST_RENDERING,
    GUEST_RENDER_COMPLETE,
    HOST_PRESENTING,
    RETIRED,
}

enum class GuestGraphicsOwnershipEvent {
    OFFER_TO_GUEST,
    CONFIRM_GUEST_IMPORT,
    BEGIN_GUEST_RENDER,
    COMPLETE_GUEST_RENDER,
    BEGIN_HOST_PRESENT,
    COMPLETE_HOST_PRESENT,
    RETIRE,
}

data class GuestGraphicsOwnershipToken(
    val resourceId: Long,
    val generation: Long,
    val sequence: Long,
    val state: GuestGraphicsOwnershipState,
) {
    val validIdentity: Boolean
        get() =
            resourceId > 0L &&
                generation > 0L &&
                sequence >= 0L
}

data class GuestGraphicsOwnershipTransition(
    val accepted: Boolean,
    val previous: GuestGraphicsOwnershipToken,
    val current: GuestGraphicsOwnershipToken,
    val event: GuestGraphicsOwnershipEvent,
    val blocker: String? = null,
)

object GuestGraphicsOwnershipProtocol {
    const val PROTOCOL_VERSION = 1

    const val BLOCKER_INVALID_IDENTITY =
        "GUEST_GRAPHICS_OWNERSHIP_INVALID_IDENTITY"
    const val BLOCKER_STALE_SEQUENCE =
        "GUEST_GRAPHICS_OWNERSHIP_STALE_SEQUENCE"
    const val BLOCKER_SEQUENCE_GAP =
        "GUEST_GRAPHICS_OWNERSHIP_SEQUENCE_GAP"
    const val BLOCKER_INVALID_TRANSITION =
        "GUEST_GRAPHICS_OWNERSHIP_INVALID_TRANSITION"

    fun initial(
        resourceId: Long,
        generation: Long,
    ): GuestGraphicsOwnershipToken? =
        GuestGraphicsOwnershipToken(
            resourceId = resourceId,
            generation = generation,
            sequence = 0L,
            state = GuestGraphicsOwnershipState.HOST_AVAILABLE,
        ).takeIf { it.validIdentity }

    fun transition(
        token: GuestGraphicsOwnershipToken,
        event: GuestGraphicsOwnershipEvent,
        sequence: Long,
    ): GuestGraphicsOwnershipTransition {
        if (!token.validIdentity) {
            return rejected(
                token,
                event,
                BLOCKER_INVALID_IDENTITY,
            )
        }
        if (sequence <= token.sequence) {
            return rejected(
                token,
                event,
                BLOCKER_STALE_SEQUENCE,
            )
        }
        if (sequence != token.sequence + 1L) {
            return rejected(
                token,
                event,
                BLOCKER_SEQUENCE_GAP,
            )
        }

        val nextState =
            when (token.state to event) {
                GuestGraphicsOwnershipState.HOST_AVAILABLE to
                    GuestGraphicsOwnershipEvent.OFFER_TO_GUEST ->
                    GuestGraphicsOwnershipState.OFFERED_TO_GUEST

                GuestGraphicsOwnershipState.OFFERED_TO_GUEST to
                    GuestGraphicsOwnershipEvent.CONFIRM_GUEST_IMPORT ->
                    GuestGraphicsOwnershipState.GUEST_IMPORTED

                GuestGraphicsOwnershipState.GUEST_IMPORTED to
                    GuestGraphicsOwnershipEvent.BEGIN_GUEST_RENDER ->
                    GuestGraphicsOwnershipState.GUEST_RENDERING

                GuestGraphicsOwnershipState.GUEST_RENDERING to
                    GuestGraphicsOwnershipEvent.COMPLETE_GUEST_RENDER ->
                    GuestGraphicsOwnershipState.GUEST_RENDER_COMPLETE

                GuestGraphicsOwnershipState.GUEST_RENDER_COMPLETE to
                    GuestGraphicsOwnershipEvent.BEGIN_HOST_PRESENT ->
                    GuestGraphicsOwnershipState.HOST_PRESENTING

                GuestGraphicsOwnershipState.HOST_PRESENTING to
                    GuestGraphicsOwnershipEvent.COMPLETE_HOST_PRESENT ->
                    GuestGraphicsOwnershipState.HOST_AVAILABLE

                GuestGraphicsOwnershipState.HOST_AVAILABLE to
                    GuestGraphicsOwnershipEvent.RETIRE ->
                    GuestGraphicsOwnershipState.RETIRED

                else -> null
            }

        if (nextState == null) {
            return rejected(
                token,
                event,
                BLOCKER_INVALID_TRANSITION,
            )
        }

        val next =
            token.copy(
                sequence = sequence,
                state = nextState,
            )
        return GuestGraphicsOwnershipTransition(
            accepted = true,
            previous = token,
            current = next,
            event = event,
            blocker = null,
        )
    }

    fun mayGuestWrite(
        token: GuestGraphicsOwnershipToken,
    ): Boolean =
        token.validIdentity &&
            token.state == GuestGraphicsOwnershipState.GUEST_RENDERING

    fun mayHostPresent(
        token: GuestGraphicsOwnershipToken,
    ): Boolean =
        token.validIdentity &&
            token.state == GuestGraphicsOwnershipState.HOST_PRESENTING

    private fun rejected(
        token: GuestGraphicsOwnershipToken,
        event: GuestGraphicsOwnershipEvent,
        blocker: String,
    ) =
        GuestGraphicsOwnershipTransition(
            accepted = false,
            previous = token,
            current = token,
            event = event,
            blocker = blocker,
        )
}
