package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicsPresentCopyAckHostTest {
    @Test
    fun copyCompletionDoesNotPromoteVisibleFrameOrRoblox() {
        val ack = GraphicsPresentCopyAckHost.Acknowledgement(queueFamilyIndex = 3)

        assertTrue(ack.exactSwapchainPixelsCopied)
        assertTrue(ack.returnedToExternalGeneral)
        assertFalse(ack.androidVisibleFrame)
        assertFalse(ack.robloxGameplayValidated)
    }

    @Test
    fun negativeQueueFamilyCannotRepresentCopyCompletion() {
        val ack = GraphicsPresentCopyAckHost.Acknowledgement(queueFamilyIndex = -1)

        assertFalse(ack.exactSwapchainPixelsCopied)
        assertFalse(ack.returnedToExternalGeneral)
        assertFalse(ack.androidVisibleFrame)
        assertFalse(ack.robloxGameplayValidated)
    }
}
