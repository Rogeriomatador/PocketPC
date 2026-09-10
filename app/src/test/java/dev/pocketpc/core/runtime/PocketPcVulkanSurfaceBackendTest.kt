package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketPcVulkanSurfaceBackendTest {
    private fun capabilities(
        androidSurface: Boolean = true,
        headlessSurface: Boolean = false,
        swapchain: Boolean = true,
    ) =
        VulkanWsiCapabilitySnapshot(
            status = "ok",
            protocol = VulkanWsiCapabilitySnapshot.CURRENT_PROTOCOL,
            vendorId = 1L,
            deviceId = 2L,
            instanceExtensionCount = 12,
            deviceExtensionCount = 24,
            khrSurface = true,
            khrAndroidSurface = androidSurface,
            extHeadlessSurface = headlessSurface,
            khrExternalMemoryCapabilities = true,
            khrSwapchain = swapchain,
            androidExternalMemoryAhb = true,
            khrExternalMemory = true,
            khrExternalMemoryFd = false,
            khrTimelineSemaphore = true,
            khrSynchronization2 = true,
            raw = "test",
        )

    @Test
    fun advertisedAndroidSurfaceDoesNotBecomeRunnable() {
        val selection =
            PocketPcVulkanSurfaceBackendProbe.assess(
                capabilities(),
            )

        val android = selection.candidates.first {
            it.kind == PocketPcVulkanSurfaceBackendKind.ANDROID_NATIVE_SURFACE
        }
        assertTrue(android.capabilityAdvertised)
        assertFalse(android.implemented)
        assertFalse(android.runnable)
        assertEquals(PocketPcVulkanSurfaceBackendKind.NONE, selection.selected.kind)
        assertFalse(selection.runnable)
    }

    @Test
    fun advertisedHeadlessSurfaceEnablesOnlyDiagnosticBackend() {
        val selection =
            PocketPcVulkanSurfaceBackendProbe.assess(
                capabilities(
                    androidSurface = false,
                    headlessSurface = true,
                ),
            )

        val diagnostic = selection.candidates.first {
            it.kind == PocketPcVulkanSurfaceBackendKind.HEADLESS_DIAGNOSTIC
        }
        val visibleShim = selection.candidates.first {
            it.kind == PocketPcVulkanSurfaceBackendKind.HEADLESS_SURFACE_SHIM
        }

        assertTrue(diagnostic.capabilityAdvertised)
        assertTrue(diagnostic.implemented)
        assertTrue(diagnostic.runnable)
        assertFalse(diagnostic.productionRunnable)
        assertTrue(selection.diagnosticRunnable)

        assertTrue(visibleShim.capabilityAdvertised)
        assertFalse(visibleShim.implemented)
        assertFalse(visibleShim.runnable)
        assertEquals(
            PocketPcVulkanSurfaceBackendProbe.BLOCKER_HEADLESS_PRESENT_CAPTURE,
            visibleShim.blocker,
        )
        assertEquals(PocketPcVulkanSurfaceBackendKind.NONE, selection.selected.kind)
        assertFalse(selection.runnable)
    }

    @Test
    fun diagnosticIsUnavailableWithoutHeadlessExtension() {
        val selection =
            PocketPcVulkanSurfaceBackendProbe.assess(
                capabilities(
                    androidSurface = false,
                    headlessSurface = false,
                ),
            )

        assertFalse(selection.diagnosticRunnable)
        assertFalse(selection.runnable)
    }

    @Test
    fun ahardwareBufferIsNeverClassifiedAsSurfaceBackend() {
        val selection =
            PocketPcVulkanSurfaceBackendProbe.assess(capabilities())

        assertFalse(selection.ahardwareBufferIsSurface)
        assertFalse(
            selection.candidates.any {
                it.detail.contains("AHardwareBuffer é uma VkSurfaceKHR")
            },
        )
    }

    @Test
    fun missingCapabilityProbeFailsClosed() {
        val selection =
            PocketPcVulkanSurfaceBackendProbe.assess(null)

        assertEquals(PocketPcVulkanSurfaceBackendKind.NONE, selection.selected.kind)
        assertFalse(selection.runnable)
        assertFalse(selection.diagnosticRunnable)
        assertTrue(selection.candidates.none { it.capabilityAdvertised })
    }
}
