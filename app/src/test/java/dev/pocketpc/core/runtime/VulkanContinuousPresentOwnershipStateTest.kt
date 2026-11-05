package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanContinuousPresentOwnershipStateTest {
    private val generation1 =
        VulkanContinuousPresentResourceIdentity(
            resourceId = 7L,
            generation = 11L,
            offerSequence = 1L,
        )

    @Test
    fun successfulFramesAdvanceOnlyAfterExternalReturn() {
        val state = VulkanContinuousPresentOwnershipState(generation1)

        val first = state.beginHostConsume(generation1, 1L).getOrThrow()
        assertEquals(1L, first.frameSequence)
        assertEquals(2L, first.hostConsumedValue)
        assertEquals(VulkanContinuousPresentHostState.HOST_CONSUMING, state.currentState)

        assertEquals(2L, state.complete(first, returnedExternal = true).getOrThrow())
        assertEquals(2L, state.expectedFrameSequence)
        assertEquals(VulkanContinuousPresentHostState.WAITING_GUEST, state.currentState)

        val second = state.beginHostConsume(generation1, 3L).getOrThrow()
        assertEquals(2L, second.frameSequence)
        assertEquals(4L, state.complete(second, returnedExternal = true).getOrThrow())
        assertEquals(3L, state.expectedFrameSequence)
    }

    @Test
    fun duplicateAndSkippedFramesFailClosed() {
        val state = VulkanContinuousPresentOwnershipState(generation1)
        val first = state.beginHostConsume(generation1, 1L).getOrThrow()
        state.complete(first, returnedExternal = true).getOrThrow()

        assertTrue(state.beginHostConsume(generation1, 1L).isFailure)
        assertTrue(state.beginHostConsume(generation1, 5L).isFailure)
        assertFalse(state.poisoned)
        assertEquals(2L, state.expectedFrameSequence)
    }

    @Test
    fun staleGenerationCannotInjectFrame() {
        val state = VulkanContinuousPresentOwnershipState(generation1)
        val wrong = generation1.copy(generation = generation1.generation + 1L)

        assertTrue(state.beginHostConsume(wrong, 1L).isFailure)
        assertEquals(VulkanContinuousPresentHostState.WAITING_GUEST, state.currentState)
    }

    @Test
    fun failedReadbackCanRetryOnlyWhenExternalOwnershipWasReturned() {
        val retryable = VulkanContinuousPresentOwnershipState(generation1)
        val retryLease = retryable.beginHostConsume(generation1, 1L).getOrThrow()
        retryable.abort(retryLease, returnedExternal = true).getOrThrow()
        assertFalse(retryable.poisoned)
        assertTrue(retryable.beginHostConsume(generation1, 1L).isSuccess)

        val poisoned = VulkanContinuousPresentOwnershipState(generation1)
        val poisonLease = poisoned.beginHostConsume(generation1, 1L).getOrThrow()
        poisoned.abort(poisonLease, returnedExternal = false).getOrThrow()
        assertTrue(poisoned.poisoned)
        assertTrue(poisoned.beginHostConsume(generation1, 1L).isFailure)
    }

    @Test
    fun completeWithoutExternalReturnPoisonsGeneration() {
        val state = VulkanContinuousPresentOwnershipState(generation1)
        val lease = state.beginHostConsume(generation1, 1L).getOrThrow()

        assertTrue(state.complete(lease, returnedExternal = false).isFailure)
        assertTrue(state.poisoned)
        assertEquals(
            VulkanContinuousPresentHostState.POISONED_RECREATE_GENERATION,
            state.currentState,
        )
    }

    @Test
    fun newerGenerationResetsFrameSequenceButSameIdentityCannotUnpoison() {
        val state = VulkanContinuousPresentOwnershipState(generation1)
        val lease = state.beginHostConsume(generation1, 1L).getOrThrow()
        state.abort(lease, returnedExternal = false).getOrThrow()

        assertTrue(state.replaceGeneration(generation1).isFailure)

        val generation2 = generation1.copy(generation = 12L)
        state.replaceGeneration(generation2).getOrThrow()

        assertFalse(state.poisoned)
        assertEquals(1L, state.expectedFrameSequence)
        assertEquals(generation2, state.currentResource)
        assertTrue(state.beginHostConsume(generation2, 1L).isSuccess)
    }

    @Test
    fun replacementMustMoveForwardOrUseDifferentResource() {
        val state = VulkanContinuousPresentOwnershipState(generation1)

        assertTrue(
            state.replaceGeneration(
                generation1.copy(generation = generation1.generation - 1L),
            ).isFailure,
        )

        val newResource =
            VulkanContinuousPresentResourceIdentity(
                resourceId = 8L,
                generation = 1L,
                offerSequence = 1L,
            )
        assertTrue(state.replaceGeneration(newResource).isSuccess)
    }
}
