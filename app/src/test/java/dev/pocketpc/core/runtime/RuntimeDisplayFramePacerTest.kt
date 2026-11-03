package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeDisplayFramePacerTest {
    @Test
    fun firstFrameIsNotDelayed() {
        val pacer = RuntimeDisplayFramePacer()
        assertEquals(0L, pacer.recommendedDelayMillis(1_000_000_000L, null))
    }

    @Test
    fun softwareFramesAreLimitedToSixtyPerSecond() {
        val pacer = RuntimeDisplayFramePacer()
        pacer.recordAcknowledgement(1_000_000_000L)
        assertEquals(17L, pacer.recommendedDelayMillis(1_000_000_000L, 60))
        assertEquals(12L, pacer.recommendedDelayMillis(1_005_000_000L, 120))
        assertEquals(0L, pacer.recommendedDelayMillis(1_020_000_000L, null))
    }

    @Test
    fun pressureOrManualLowerRateReducesFrameCopyFrequency() {
        val pacer = RuntimeDisplayFramePacer()
        pacer.recordAcknowledgement(1_000_000_000L)
        assertEquals(34L, pacer.recommendedDelayMillis(1_000_000_000L, 30))
        assertEquals(23L, pacer.recommendedDelayMillis(1_000_000_000L, 45))
        assertEquals(67L, pacer.recommendedDelayMillis(1_000_000_000L, 1))
    }

    @Test
    fun resettingMonotonicTimeDoesNotCauseAnUnboundedWait() {
        val pacer = RuntimeDisplayFramePacer()
        pacer.recordAcknowledgement(500L)
        assertEquals(0L, pacer.recommendedDelayMillis(499L, 30))
    }
}
