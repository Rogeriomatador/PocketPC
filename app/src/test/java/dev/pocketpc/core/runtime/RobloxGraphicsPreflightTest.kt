package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RobloxGraphicsPreflightTest {
    private fun nativeHost(
        loaded: Boolean = true,
    ) =
        NativeHostStatus(
            loaded = loaded,
            probe =
                if (loaded) {
                    "native-host=loaded"
                } else {
                    "native-host=load-failed"
                },
            graphicsProbe = "vulkan=available",
            nativeLibraryDir = "/data/app/lib",
            hardwareBufferProbe =
                if (loaded) {
                    "ahardwarebuffer=available;send_handle=ok;recv_handle=ok;" +
                        "descriptor_match=yes;cross_process_transport=structurally-ready;" +
                        "vulkan_wsi=not-tested"
                } else {
                    "ahardwarebuffer=not-probed"
                },
        )

    private fun verifiedCrossProcessEvidence() =
        HardwareBufferCrossProcessEvidence(
            senderPid = 100,
            receiverPid = 101,
            senderResult = "ahb-xproc-send=ok;width=64;height=64",
            receiverResult =
                "ahb-xproc-recv=ok;descriptor_match=yes;pattern_match=yes",
            distinctProcesses = true,
            handleTransportVerified = true,
            vulkanWsiValidated = false,
            error = null,
        )

    @Test
    fun androidCrossProcessTransportDoesNotClaimGuestGraphicsReadiness() {
        val result =
            RobloxGraphicsPreflightCoordinator.buildResult(
                nativeHost = nativeHost(),
                crossProcessEvidence = verifiedCrossProcessEvidence(),
            )

        assertFalse(result.wsiFoundation.readyForWsiImplementation)
        assertFalse(result.wsiFoundation.guestGraphicsTransportReady)
        assertTrue(
            result.wsiFoundation.blockers.contains(
                PocketPcVulkanWsiFoundationProbe.BLOCKER_GUEST_GRAPHICS_TRANSPORT,
            ),
        )
        assertFalse(result.wsiImplemented)
        assertFalse(result.readyForWsiIntegrationTest)
        assertFalse(result.readyForRobloxGraphics)
    }

    @Test
    fun missingCrossProcessEvidenceFailsClosed() {
        val result =
            RobloxGraphicsPreflightCoordinator.buildResult(
                nativeHost = nativeHost(),
                crossProcessEvidence = null,
            )

        assertFalse(result.wsiFoundation.readyForWsiImplementation)
        assertFalse(result.readyForWsiIntegrationTest)
        assertFalse(result.readyForRobloxGraphics)
    }

    @Test
    fun nativeHostFailureCannotBeHiddenByTransportEvidence() {
        val result =
            RobloxGraphicsPreflightCoordinator.buildResult(
                nativeHost = nativeHost(loaded = false),
                crossProcessEvidence = verifiedCrossProcessEvidence(),
            )

        assertFalse(result.wsiFoundation.readyForWsiImplementation)
        assertFalse(result.readyForRobloxGraphics)
        assertTrue(result.wsiFoundation.blockers.isNotEmpty())
    }
}
