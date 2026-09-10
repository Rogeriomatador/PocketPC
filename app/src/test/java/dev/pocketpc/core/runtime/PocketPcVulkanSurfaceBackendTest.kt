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
    fun advertisedHeadlessSurfaceStillNeedsPresentCaptureBackend() {
        val selection =
            PocketPcVulkanSurfaceBackendProbe.assess(
                capabilities(
                    androidSurface = false,
                    headlessSurface = true,
                ),
            )

        val headless = selection.candidates.first {
            it.kind == PocketPcVulkanSurfaceBackendKind.HEADLESS_SURFACE_SHIM
        }
        assertTrue(headless.capabilityAdvertised)
        assertFalse(headless.implemented)
        assertEquals(
            PocketPcVulkanSurfaceBackendProbe.BLOCKER_HEADLESS_PRESENT_CAPTURE,
            headless.blocker,
        )
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
        assertTrue(selection.candidates.none { it.capabilityAdvertised })
    }
}
