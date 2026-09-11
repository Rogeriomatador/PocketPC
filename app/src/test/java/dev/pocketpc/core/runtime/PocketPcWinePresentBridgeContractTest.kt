package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketPcWinePresentBridgeContractTest {
    @Test
    fun sourceIntegrationDoesNotPromoteRuntimeOrRoblox() {
        assertTrue(
            PocketPcWinePresentBridgeContract
                .sourceIntegratedForPixelCopyAttempt(),
        )
        assertFalse(PocketPcWinePresentBridgeContract.pixelCopyImplemented)
        assertFalse(
            PocketPcWinePresentBridgeContract
                .androidVisiblePresentImplemented,
        )
        assertFalse(PocketPcWinePresentBridgeContract.runtimeExecuted)
        assertFalse(PocketPcWinePresentBridgeContract.robloxExecuted)
        assertFalse(
            PocketPcWinePresentBridgeContract
                .readyForRobloxGraphics(),
        )
    }

    @Test
    fun blockersNameTheRemainingPresentAndRobloxGates() {
        val blockers = PocketPcWinePresentBridgeContract.blockers()

        assertTrue(
            blockers.contains(
                PocketPcWinePresentBridgeContract.BLOCKER_PIXEL_COPY,
            ),
        )
        assertTrue(
            blockers.contains(
                PocketPcWinePresentBridgeContract.BLOCKER_ANDROID_VISIBLE_PRESENT,
            ),
        )
        assertTrue(
            blockers.contains(
                PocketPcWinePresentBridgeContract.BLOCKER_PRESENT_RUNTIME,
            ),
        )
        assertTrue(
            blockers.contains(
                PocketPcWinePresentBridgeContract.BLOCKER_ROBLOX_EXECUTION,
            ),
        )
    }
}
