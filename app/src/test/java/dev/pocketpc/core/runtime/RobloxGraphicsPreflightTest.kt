package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RobloxGraphicsPreflightTest {
    private val validVulkanCapabilities =
        "vulkan-wsi-capabilities=ok;protocol=1;vendor_id=1234;device_id=5678;" +
            "instance_extensions=12;device_extensions=34;" +
            "khr_surface=yes;khr_android_surface=yes;ext_headless_surface=no;" +
            "khr_external_memory_capabilities=yes;khr_swapchain=yes;" +
            "android_external_memory_ahb=yes;khr_external_memory=yes;" +
            "khr_external_memory_fd=yes;khr_timeline_semaphore=yes;" +
            "khr_synchronization2=yes"

    private fun nativeHost(
        loaded: Boolean = true,
        vulkanCapabilities: String = validVulkanCapabilities,
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
            vulkanWsiCapabilityProbe = vulkanCapabilities,
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
    fun advertisedAndroidSurfaceStillDoesNotCreateExecutableBackend() {
        val result =
            RobloxGraphicsPreflightCoordinator.buildResult(
                nativeHost = nativeHost(),
                crossProcessEvidence = verifiedCrossProcessEvidence(),
            )

        assertTrue(result.capabilityProbeSucceeded)
        assertTrue(result.androidSurfaceRouteAdvertised)
        assertFalse(result.surfaceBackendRunnable)
        assertEquals(
            PocketPcVulkanSurfaceBackendKind.NONE,
            result.surfaceBackend.selected.kind,
        )
        assertTrue(
            result.blockers.contains(
                RobloxGraphicsPreflightCoordinator.BLOCKER_SURFACE_BACKEND,
            ),
        )
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
    fun malformedVulkanCapabilityEvidenceCannotCreateSurfaceBackend() {
        val result =
            RobloxGraphicsPreflightCoordinator.buildResult(
                nativeHost =
                    nativeHost(
                        vulkanCapabilities =
                            "vulkan-wsi-capabilities=ok;protocol=1;khr_surface=yes",
                    ),
                crossProcessEvidence = verifiedCrossProcessEvidence(),
            )

        assertFalse(result.capabilityProbeSucceeded)
        assertFalse(result.surfaceBackendRunnable)
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
