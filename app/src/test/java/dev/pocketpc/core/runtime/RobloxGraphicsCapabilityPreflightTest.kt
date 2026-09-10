package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RobloxGraphicsCapabilityPreflightTest {
    private fun nativeHost(capabilities: String) =
        NativeHostStatus(
            loaded = true,
            probe = "native-host=loaded",
            graphicsProbe = "vulkan=available",
            nativeLibraryDir = "/data/app/lib",
            hardwareBufferProbe =
                "ahardwarebuffer=available;send_handle=ok;recv_handle=ok;" +
                    "descriptor_match=yes;cross_process_transport=structurally-ready;" +
                    "vulkan_wsi=not-tested",
            vulkanWsiCapabilityProbe = capabilities,
        )

    private fun crossProcessEvidence() =
        HardwareBufferCrossProcessEvidence(
            senderPid = 100,
            receiverPid = 101,
            senderResult =
                "ahb-xproc-send=ok;protocol=1;pid=100;width=64;height=64;" +
                    "layers=1;format=1;stride=64;lock=0;unlock=0;send=0",
            receiverResult =
                "ahb-xproc-recv=ok;protocol=1;pid=101;width=64;height=64;" +
                    "layers=1;format=1;stride=64;recv=0;lock=0;unlock=0;" +
                    "descriptor_match=yes;pattern_match=yes",
            distinctProcesses = true,
            handleTransportVerified = true,
            vulkanWsiValidated = false,
            error = null,
        )

    private fun capabilities(
        androidSurface: String = "yes",
        headless: String = "no",
        ahb: String = "yes",
    ) =
        "vulkan-wsi-capabilities=ok;protocol=1;vendor_id=1234;device_id=5678;" +
            "instance_extensions=12;device_extensions=34;" +
            "khr_surface=yes;khr_android_surface=$androidSurface;" +
            "ext_headless_surface=$headless;khr_external_memory_capabilities=yes;" +
            "khr_swapchain=yes;android_external_memory_ahb=$ahb;" +
            "khr_external_memory=yes;khr_external_memory_fd=yes;" +
            "khr_timeline_semaphore=yes;khr_synchronization2=yes"

    @Test
    fun advertisedAndroidRouteStillDoesNotClaimRobloxGraphics() {
        val result =
            RobloxGraphicsPreflightCoordinator.buildResult(
                nativeHost = nativeHost(capabilities()),
                crossProcessEvidence = crossProcessEvidence(),
            )

        assertTrue(result.wsiFoundation.readyForWsiImplementation)
        assertTrue(result.capabilityProbeSucceeded)
        assertTrue(result.androidSurfaceRouteAdvertised)
        assertTrue(result.ahardwareBufferExternalMemoryAdvertised)
        assertFalse(result.wsiImplemented)
        assertFalse(result.readyForWsiIntegrationTest)
        assertFalse(result.readyForRobloxGraphics)
        assertTrue(
            result.blockers.contains(
                PocketPcVulkanWsiContract.blocker,
            ),
        )
    }

    @Test
    fun missingSurfaceRouteHasDedicatedBlocker() {
        val result =
            RobloxGraphicsPreflightCoordinator.buildResult(
                nativeHost =
                    nativeHost(
                        capabilities(
                            androidSurface = "no",
                            headless = "no",
                        ),
                    ),
                crossProcessEvidence = crossProcessEvidence(),
            )

        assertFalse(result.androidSurfaceRouteAdvertised)
        assertFalse(result.headlessSurfaceRouteAdvertised)
        assertTrue(
            result.blockers.contains(
                RobloxGraphicsPreflightCoordinator
                    .BLOCKER_WSI_SURFACE_ROUTE,
            ),
        )
    }

    @Test
    fun missingAhbExternalMemoryHasDedicatedBlocker() {
        val result =
            RobloxGraphicsPreflightCoordinator.buildResult(
                nativeHost =
                    nativeHost(
                        capabilities(ahb = "no"),
                    ),
                crossProcessEvidence = crossProcessEvidence(),
            )

        assertFalse(result.ahardwareBufferExternalMemoryAdvertised)
        assertTrue(
            result.blockers.contains(
                RobloxGraphicsPreflightCoordinator
                    .BLOCKER_AHB_EXTERNAL_MEMORY,
            ),
        )
    }

    @Test
    fun malformedCapabilityEvidenceCannotBePromoted() {
        val result =
            RobloxGraphicsPreflightCoordinator.buildResult(
                nativeHost =
                    nativeHost(
                        capabilities().replace(
                            "khr_swapchain=yes",
                            "khr_swapchain=maybe",
                        ),
                    ),
                crossProcessEvidence = crossProcessEvidence(),
            )

        assertFalse(result.capabilityProbeSucceeded)
        assertTrue(
            result.blockers.contains(
                RobloxGraphicsPreflightCoordinator
                    .BLOCKER_WSI_CAPABILITY_PROBE,
            ),
        )
        assertFalse(result.readyForRobloxGraphics)
    }
}
