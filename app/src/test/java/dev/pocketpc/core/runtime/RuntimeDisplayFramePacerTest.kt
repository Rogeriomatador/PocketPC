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
    fun highResolutionFramesHaveLowerThroughputBudget() {
        val pacer = RuntimeDisplayFramePacer()
        pacer.recordAcknowledgement(1_000_000_000L)
        // 1080p is limited by the software copy budget even with 120 FPS
        // requested. 60,000,000 / 2,073,600 = 28 FPS.
        assertEquals(
            36L,
            pacer.recommendedDelayMillis(
                1_000_000_000L, 120, frameWidth = 1920, frameHeight = 1080,
            ),
        )
        // Extremely large surfaces are limited to five copies per second.
        assertEquals(
            200L,
            pacer.recommendedDelayMillis(
                1_000_000_000L, 120, frameWidth = 4096, frameHeight = 4096,
            ),
        )
        // Small GDI windows keep the ordinary 60 FPS software limit.
        assertEquals(
            17L,
            pacer.recommendedDelayMillis(
                1_000_000_000L, 120, frameWidth = 640, frameHeight = 480,
            ),
        )
    }

    @Test
    fun resettingMonotonicTimeDoesNotCauseAnUnboundedWait() {
        val pacer = RuntimeDisplayFramePacer()
        pacer.recordAcknowledgement(500L)
        assertEquals(0L, pacer.recommendedDelayMillis(499L, 30))
    }
}
