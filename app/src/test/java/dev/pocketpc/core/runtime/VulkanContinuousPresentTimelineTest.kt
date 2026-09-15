package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VulkanContinuousPresentTimelineTest {
    @Test
    fun firstFramesAlternateGuestReadyAndHostConsumed() {
        assertEquals(1L, VulkanContinuousPresentTimeline.guestReadyValue(1L))
        assertEquals(2L, VulkanContinuousPresentTimeline.hostConsumedValue(1L))
        assertEquals(3L, VulkanContinuousPresentTimeline.guestReadyValue(2L))
        assertEquals(4L, VulkanContinuousPresentTimeline.hostConsumedValue(2L))
        assertEquals(5L, VulkanContinuousPresentTimeline.guestReadyValue(3L))
        assertEquals(6L, VulkanContinuousPresentTimeline.hostConsumedValue(3L))
    }

    @Test
    fun decodersRejectWrongParityAndOutOfRangeValues() {
        assertEquals(1L, VulkanContinuousPresentTimeline.frameSequenceFromGuestReady(1L))
        assertEquals(2L, VulkanContinuousPresentTimeline.frameSequenceFromGuestReady(3L))
        assertNull(VulkanContinuousPresentTimeline.frameSequenceFromGuestReady(0L))
        assertNull(VulkanContinuousPresentTimeline.frameSequenceFromGuestReady(2L))
        assertNull(VulkanContinuousPresentTimeline.frameSequenceFromGuestReady(Long.MAX_VALUE))

        assertEquals(1L, VulkanContinuousPresentTimeline.frameSequenceFromHostConsumed(2L))
        assertEquals(2L, VulkanContinuousPresentTimeline.frameSequenceFromHostConsumed(4L))
        assertNull(VulkanContinuousPresentTimeline.frameSequenceFromHostConsumed(0L))
        assertNull(VulkanContinuousPresentTimeline.frameSequenceFromHostConsumed(1L))
        assertNull(VulkanContinuousPresentTimeline.frameSequenceFromHostConsumed(Long.MAX_VALUE))
    }

    @Test
    fun maximumFrameStaysInsideSignedJniRange() {
        val frame = VulkanContinuousPresentTimeline.MAX_FRAME_SEQUENCE

        assertEquals(Long.MAX_VALUE - 2L, VulkanContinuousPresentTimeline.guestReadyValue(frame))
        assertEquals(Long.MAX_VALUE - 1L, VulkanContinuousPresentTimeline.hostConsumedValue(frame))
        assertEquals(
            frame,
            VulkanContinuousPresentTimeline.frameSequenceFromGuestReady(Long.MAX_VALUE - 2L),
        )
        assertEquals(
            frame,
            VulkanContinuousPresentTimeline.frameSequenceFromHostConsumed(Long.MAX_VALUE - 1L),
        )
    }

    @Test
    fun invalidFrameSequenceFailsClosed() {
        for (frame in listOf(0L, -1L, VulkanContinuousPresentTimeline.MAX_FRAME_SEQUENCE + 1L)) {
            try {
                VulkanContinuousPresentTimeline.guestReadyValue(frame)
                fail("guestReadyValue accepted invalid frame $frame")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }

            try {
                VulkanContinuousPresentTimeline.hostConsumedValue(frame)
                fail("hostConsumedValue accepted invalid frame $frame")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun guestCannotSkipOwnershipTransitions() {
        assertTrue(VulkanContinuousPresentTimeline.canAdvanceAfterHostConsumed(1L, 0L))
        assertFalse(VulkanContinuousPresentTimeline.canAdvanceAfterHostConsumed(1L, 1L))

        assertTrue(VulkanContinuousPresentTimeline.canAdvanceAfterHostConsumed(2L, 2L))
        assertFalse(VulkanContinuousPresentTimeline.canAdvanceAfterHostConsumed(2L, 1L))
        assertFalse(VulkanContinuousPresentTimeline.canAdvanceAfterHostConsumed(2L, 3L))

        assertTrue(VulkanContinuousPresentTimeline.canAdvanceAfterHostConsumed(3L, 4L))
        assertFalse(VulkanContinuousPresentTimeline.canAdvanceAfterHostConsumed(3L, 6L))
    }

    @Test
    fun parityHelpersStayFailClosed() {
        assertFalse(VulkanContinuousPresentTimeline.isHostConsumedValue(-2L))
        assertTrue(VulkanContinuousPresentTimeline.isHostConsumedValue(0L))
        assertFalse(VulkanContinuousPresentTimeline.isGuestReadyValue(0L))
        assertTrue(VulkanContinuousPresentTimeline.isGuestReadyValue(1L))
        assertTrue(VulkanContinuousPresentTimeline.isHostConsumedValue(2L))
        assertFalse(VulkanContinuousPresentTimeline.isGuestReadyValue(2L))
    }
}
