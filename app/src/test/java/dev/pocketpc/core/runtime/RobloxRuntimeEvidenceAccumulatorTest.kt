package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RobloxRuntimeEvidenceAccumulatorTest {
    private val fingerprint = "ab".repeat(32)

    @Test
    fun stableDurationStartsWhenPlayerProcessIsObserved() {
        val accumulator =
            RobloxRuntimeEvidenceAccumulator(
                fingerprint = fingerprint,
                startedAtElapsedMillis = 1_000L,
            )

        accumulator.observe(
            RobloxObservedSignal.PLAYER_PROCESS_STARTED,
            elapsedMillis = 5_000L,
        )
        val evidence =
            accumulator.snapshot(
                expectedFingerprint = fingerprint,
                elapsedMillis = 65_000L,
            )

        assertTrue(evidence.playerProcessStarted)
        assertEquals(60_000L, evidence.stableSessionMillis)
    }

    @Test
    fun signalsBeforeProcessStartDoNotPromoteApplicationEvidence() {
        val accumulator =
            RobloxRuntimeEvidenceAccumulator(
                fingerprint = fingerprint,
                startedAtElapsedMillis = 10L,
            )

        accumulator.observe(
            RobloxObservedSignal.PLAYER_WINDOW_PRESENTED,
            elapsedMillis = 20L,
        )
        accumulator.observe(
            RobloxObservedSignal.D3D11_PRESENT_OBSERVED,
            elapsedMillis = 30L,
        )
        accumulator.observe(
            RobloxObservedSignal.EXTERNAL_NETWORK_OBSERVED,
            elapsedMillis = 40L,
        )
        val evidence =
            accumulator.snapshot(
                expectedFingerprint = fingerprint,
                elapsedMillis = 1_000L,
            )

        assertFalse(evidence.playerProcessStarted)
        assertFalse(evidence.playerWindowPresented)
        assertFalse(evidence.d3d11PresentObserved)
        assertFalse(evidence.externalNetworkObserved)
        assertEquals(0L, evidence.stableSessionMillis)
    }

    @Test
    fun processExitFreezesDurationAndCrashRemainsSticky() {
        val accumulator =
            RobloxRuntimeEvidenceAccumulator(
                fingerprint = fingerprint,
                startedAtElapsedMillis = 0L,
            )
        accumulator.observe(
            RobloxObservedSignal.PLAYER_PROCESS_STARTED,
            elapsedMillis = 100L,
        )
        accumulator.observe(
            RobloxObservedSignal.CRASH_OBSERVED,
            elapsedMillis = 3_100L,
        )
        accumulator.processExited(
            elapsedMillis = 4_100L,
            crashed = false,
        )

        val evidence =
            accumulator.snapshot(
                expectedFingerprint = fingerprint,
                elapsedMillis = 20_000L,
            )
        assertEquals(3_000L, evidence.stableSessionMillis)
        assertTrue(evidence.crashObserved)
        assertFalse(evidence.integrationSmokePassed)
    }

    @Test
    fun gameplayRequiresServerAndObservedPlayerInteraction() {
        val accumulator =
            RobloxRuntimeEvidenceAccumulator(
                fingerprint = fingerprint,
                startedAtElapsedMillis = 0L,
            )
        accumulator.observe(
            RobloxObservedSignal.PLAYER_PROCESS_STARTED,
            elapsedMillis = 100L,
        )
        accumulator.observe(
            RobloxObservedSignal.PLAYER_WINDOW_PRESENTED,
            elapsedMillis = 200L,
        )
        accumulator.observe(
            RobloxObservedSignal.D3D11_PRESENT_OBSERVED,
            elapsedMillis = 300L,
        )
        accumulator.observe(
            RobloxObservedSignal.EXTERNAL_NETWORK_OBSERVED,
            elapsedMillis = 400L,
        )
        accumulator.observe(
            RobloxObservedSignal.AUDIO_OUTPUT_OBSERVED,
            elapsedMillis = 500L,
        )
        accumulator.observe(
            RobloxObservedSignal.KEYBOARD_INPUT_OBSERVED,
            elapsedMillis = 600L,
        )
        accumulator.observe(
            RobloxObservedSignal.POINTER_INPUT_OBSERVED,
            elapsedMillis = 700L,
        )
        accumulator.observe(
            RobloxObservedSignal.SERVER_SESSION_JOINED,
            elapsedMillis = 800L,
        )
        accumulator.observe(
            RobloxObservedSignal.AVATAR_MOVEMENT_OBSERVED,
            elapsedMillis = 900L,
        )
        accumulator.observe(
            RobloxObservedSignal.GAMEPLAY_INTERACTION_OBSERVED,
            elapsedMillis = 1_000L,
        )

        val evidence =
            accumulator.snapshot(
                expectedFingerprint = fingerprint,
                elapsedMillis = 300_100L,
            )

        assertTrue(evidence.serverSessionJoined)
        assertTrue(evidence.avatarMovementObserved)
        assertTrue(evidence.gameplayInteractionObserved)
        assertTrue(evidence.gameplayValidated)
    }

    @Test
    fun gameplaySignalsAreRejectedBeforeServerEvidence() {
        val accumulator =
            RobloxRuntimeEvidenceAccumulator(
                fingerprint = fingerprint,
                startedAtElapsedMillis = 0L,
            )
        accumulator.observe(
            RobloxObservedSignal.PLAYER_PROCESS_STARTED,
            elapsedMillis = 100L,
        )
        accumulator.observe(
            RobloxObservedSignal.KEYBOARD_INPUT_OBSERVED,
            elapsedMillis = 200L,
        )
        accumulator.observe(
            RobloxObservedSignal.AVATAR_MOVEMENT_OBSERVED,
            elapsedMillis = 300L,
        )
        accumulator.observe(
            RobloxObservedSignal.GAMEPLAY_INTERACTION_OBSERVED,
            elapsedMillis = 400L,
        )

        val evidence =
            accumulator.snapshot(
                expectedFingerprint = fingerprint,
                elapsedMillis = 400_000L,
            )

        assertFalse(evidence.serverSessionJoined)
        assertFalse(evidence.avatarMovementObserved)
        assertFalse(evidence.gameplayInteractionObserved)
        assertFalse(evidence.gameplayValidated)
    }

    @Test(expected = IllegalArgumentException::class)
    fun fingerprintChangeIsRejected() {
        val accumulator =
            RobloxRuntimeEvidenceAccumulator(
                fingerprint = fingerprint,
                startedAtElapsedMillis = 0L,
            )
        accumulator.snapshot(
            expectedFingerprint = "cd".repeat(32),
            elapsedMillis = 1L,
        )
    }
}
