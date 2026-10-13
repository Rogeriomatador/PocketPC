package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RobloxGraphicsCapabilityPreflightTest {
    private fun nativeHost(
        capabilities: String,
        external: String = externalCapabilities(),
        ahbImport: String = canonicalAhbImport(),
    ) =
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
            vulkanExternalResourceProbe = external,
            vulkanAhardwareBufferImportProbe = ahbImport,
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

    companion object {
        private fun externalCapabilities() =
            "vulkan-external-resource=ok;protocol=1;vendor_id=1234;device_id=5678;" +
                "instance_extensions=12;device_extensions=34;" +
                "khr_external_memory_capabilities=yes;khr_external_memory=yes;" +
                "khr_external_memory_fd=yes;ext_external_memory_dma_buf=yes;" +
                "android_external_memory_ahb=yes;khr_external_semaphore=yes;" +
                "khr_external_semaphore_fd=yes;khr_external_fence=yes;" +
                "khr_external_fence_fd=yes;query_external_buffer_properties=yes;" +
                "opaque_fd_queried=yes;opaque_fd_importable=yes;opaque_fd_exportable=yes;" +
                "ahb_queried=yes;ahb_importable=yes;ahb_exportable=yes;" +
                "dma_buf_queried=yes;dma_buf_importable=yes;dma_buf_exportable=yes"

        private fun canonicalAhbImport() =
            "vulkan-ahb-import=ok;protocol=1;api_major=1;api_minor=3;" +
                "vendor_id=1234;device_id=5678;allocation_size=16384;" +
                "memory_type_bits=3;ahb_allocate_result=0;property_query_result=0;" +
                "vulkan_1_1_or_newer=yes;ahb_extension=yes;" +
                "foreign_queue_extension=yes;queue_family_available=yes;" +
                "device_created=yes;query_function_available=yes;ahb_allocated=yes;" +
                "properties_query_succeeded=yes;allocation_size_nonzero=yes;" +
                "memory_type_bits_nonzero=yes;canonical_import_query_supported=yes"
    }

    @Test
    fun advertisedAndroidRouteStillDoesNotClaimRobloxGraphics() {
        val result =
            RobloxGraphicsPreflightCoordinator.buildResult(
                nativeHost = nativeHost(capabilities()),
                crossProcessEvidence = crossProcessEvidence(),
            )

        assertFalse(result.wsiFoundation.readyForWsiImplementation)
        assertFalse(result.wsiFoundation.guestGraphicsTransportReady)
        assertTrue(result.capabilityProbeSucceeded)
        assertTrue(result.externalResourceProbeSucceeded)
        assertTrue(result.androidSurfaceRouteAdvertised)
        assertTrue(result.ahardwareBufferExternalMemoryAdvertised)
        assertTrue(result.canonicalAhbImportQuerySupported)
        assertEquals(
            GuestGraphicsTransportCandidate.OPAQUE_FD,
            result.guestTransportPlan.candidate,
        )
        assertFalse(result.guestTransportPlan.guestTransportReady)
        assertFalse(result.wsiImplemented)
        assertFalse(result.readyForWsiIntegrationTest)
        assertFalse(result.readyForRobloxGraphics)
        assertTrue(
            result.blockers.contains(
                PocketPcVulkanWsiFoundationProbe.BLOCKER_GUEST_GRAPHICS_TRANSPORT,
            ),
        )
    }

    @Test
    fun headlessDiagnosticIsReportedButNeverPromotedToVisibleRoblox() {
        val result =
            RobloxGraphicsPreflightCoordinator.buildResult(
                nativeHost =
                    nativeHost(
                        capabilities(
                            androidSurface = "no",
                            headless = "yes",
                        ),
                    ),
                crossProcessEvidence = crossProcessEvidence(),
            )

        assertTrue(result.headlessSurfaceRouteAdvertised)
        assertTrue(result.headlessDiagnosticRunnable)
        assertFalse(result.surfaceBackendRunnable)
        assertTrue(
            result.blockers.contains(
                RobloxGraphicsPreflightCoordinator.BLOCKER_HEADLESS_DIAGNOSTIC_ONLY,
            ),
        )
        assertFalse(result.readyForRobloxGraphics)
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

    @Test
    fun malformedExternalResourceEvidenceIsDedicatedBlocker() {
        val result =
            RobloxGraphicsPreflightCoordinator.buildResult(
                nativeHost =
                    nativeHost(
                        capabilities(),
                        external = externalCapabilities().replace(
                            "opaque_fd_importable=yes",
                            "opaque_fd_importable=maybe",
                        ),
                    ),
                crossProcessEvidence = crossProcessEvidence(),
            )

        assertFalse(result.externalResourceProbeSucceeded)
        assertTrue(
            result.blockers.contains(
                RobloxGraphicsPreflightCoordinator
                    .BLOCKER_EXTERNAL_RESOURCE_PROBE,
            ),
        )
        assertFalse(result.readyForRobloxGraphics)
    }
}
