package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestGraphicsOwnershipProtocolTest {
    @Test
    fun fullOwnershipCycleNeverAllowsHostAndGuestAtTheSameState() {
        var token =
            requireNotNull(
                GuestGraphicsOwnershipProtocol.initial(
                    resourceId = 7L,
                    generation = 2L,
                ),
            )

        val events =
            listOf(
                GuestGraphicsOwnershipEvent.OFFER_TO_GUEST,
                GuestGraphicsOwnershipEvent.CONFIRM_GUEST_IMPORT,
                GuestGraphicsOwnershipEvent.BEGIN_GUEST_RENDER,
                GuestGraphicsOwnershipEvent.COMPLETE_GUEST_RENDER,
                GuestGraphicsOwnershipEvent.BEGIN_HOST_PRESENT,
                GuestGraphicsOwnershipEvent.COMPLETE_HOST_PRESENT,
            )

        events.forEachIndexed { index, event ->
            val result =
                GuestGraphicsOwnershipProtocol.transition(
                    token = token,
                    event = event,
                    sequence = index.toLong() + 1L,
                )
            assertTrue(result.accepted)
            assertNull(result.blocker)
            token = result.current
            assertFalse(
                GuestGraphicsOwnershipProtocol.mayGuestWrite(token) &&
                    GuestGraphicsOwnershipProtocol.mayHostPresent(token),
            )
        }

        assertEquals(
            GuestGraphicsOwnershipState.HOST_AVAILABLE,
            token.state,
        )
        assertFalse(GuestGraphicsOwnershipProtocol.mayGuestWrite(token))
        assertFalse(GuestGraphicsOwnershipProtocol.mayHostPresent(token))
    }

    @Test
    fun writeAndPresentPermissionsAreNarrow() {
        val initial =
            requireNotNull(
                GuestGraphicsOwnershipProtocol.initial(1L, 1L),
            )
        val offered =
            GuestGraphicsOwnershipProtocol.transition(
                initial,
                GuestGraphicsOwnershipEvent.OFFER_TO_GUEST,
                1L,
            ).current
        val imported =
            GuestGraphicsOwnershipProtocol.transition(
                offered,
                GuestGraphicsOwnershipEvent.CONFIRM_GUEST_IMPORT,
                2L,
            ).current
        val rendering =
            GuestGraphicsOwnershipProtocol.transition(
                imported,
                GuestGraphicsOwnershipEvent.BEGIN_GUEST_RENDER,
                3L,
            ).current

        assertTrue(GuestGraphicsOwnershipProtocol.mayGuestWrite(rendering))
        assertFalse(GuestGraphicsOwnershipProtocol.mayHostPresent(rendering))

        val complete =
            GuestGraphicsOwnershipProtocol.transition(
                rendering,
                GuestGraphicsOwnershipEvent.COMPLETE_GUEST_RENDER,
                4L,
            ).current
        val presenting =
            GuestGraphicsOwnershipProtocol.transition(
                complete,
                GuestGraphicsOwnershipEvent.BEGIN_HOST_PRESENT,
                5L,
            ).current

        assertFalse(GuestGraphicsOwnershipProtocol.mayGuestWrite(presenting))
        assertTrue(GuestGraphicsOwnershipProtocol.mayHostPresent(presenting))
    }

    @Test
    fun staleAndSkippedSequencesFailClosed() {
        val initial =
            requireNotNull(
                GuestGraphicsOwnershipProtocol.initial(1L, 1L),
            )

        val stale =
            GuestGraphicsOwnershipProtocol.transition(
                initial,
                GuestGraphicsOwnershipEvent.OFFER_TO_GUEST,
                0L,
            )
        assertFalse(stale.accepted)
        assertEquals(
            GuestGraphicsOwnershipProtocol.BLOCKER_STALE_SEQUENCE,
            stale.blocker,
        )

        val gap =
            GuestGraphicsOwnershipProtocol.transition(
                initial,
                GuestGraphicsOwnershipEvent.OFFER_TO_GUEST,
                2L,
            )
        assertFalse(gap.accepted)
        assertEquals(
            GuestGraphicsOwnershipProtocol.BLOCKER_SEQUENCE_GAP,
            gap.blocker,
        )
    }

    @Test
    fun impossibleOwnershipTransitionsFailClosed() {
        val initial =
            requireNotNull(
                GuestGraphicsOwnershipProtocol.initial(1L, 1L),
            )
        val invalid =
            GuestGraphicsOwnershipProtocol.transition(
                initial,
                GuestGraphicsOwnershipEvent.BEGIN_HOST_PRESENT,
                1L,
            )

        assertFalse(invalid.accepted)
        assertEquals(initial, invalid.current)
        assertEquals(
            GuestGraphicsOwnershipProtocol.BLOCKER_INVALID_TRANSITION,
            invalid.blocker,
        )
    }

    @Test
    fun invalidIdentityCannotEnterProtocol() {
        assertNull(GuestGraphicsOwnershipProtocol.initial(0L, 1L))
        assertNull(GuestGraphicsOwnershipProtocol.initial(1L, 0L))

        val malformed =
            GuestGraphicsOwnershipToken(
                resourceId = 0L,
                generation = 1L,
                sequence = 0L,
                state = GuestGraphicsOwnershipState.HOST_AVAILABLE,
            )
        val result =
            GuestGraphicsOwnershipProtocol.transition(
                malformed,
                GuestGraphicsOwnershipEvent.OFFER_TO_GUEST,
                1L,
            )
        assertFalse(result.accepted)
        assertNotNull(result.blocker)
    }
}
