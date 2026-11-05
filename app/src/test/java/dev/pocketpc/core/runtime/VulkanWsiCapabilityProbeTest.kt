package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanWsiCapabilityProbeTest {
    private val valid =
        "vulkan-wsi-capabilities=ok;protocol=1;vendor_id=1234;device_id=5678;" +
            "instance_extensions=12;device_extensions=34;" +
            "khr_surface=yes;khr_android_surface=yes;ext_headless_surface=no;" +
            "khr_external_memory_capabilities=yes;khr_swapchain=yes;" +
            "android_external_memory_ahb=yes;khr_external_memory=yes;" +
            "khr_external_memory_fd=yes;khr_timeline_semaphore=yes;" +
            "khr_synchronization2=yes"

    @Test
    fun completeAndroidSurfaceRecordIsRecognizedButNotPromotedToWsi() {
        val result = VulkanWsiCapabilityProbeParser.parse(valid)

        assertTrue(result?.probeSucceeded == true)
        assertTrue(result?.androidSurfaceRouteAdvertised == true)
        assertTrue(result?.ahardwareBufferExternalMemoryAdvertised == true)
        assertFalse(PocketPcVulkanWsiContract.implemented)
    }

    @Test
    fun headlessRouteIsReportedSeparately() {
        val result =
            VulkanWsiCapabilityProbeParser.parse(
                valid
                    .replace("khr_android_surface=yes", "khr_android_surface=no")
                    .replace("ext_headless_surface=no", "ext_headless_surface=yes"),
            )

        assertTrue(result?.probeSucceeded == true)
        assertFalse(result?.androidSurfaceRouteAdvertised == true)
        assertTrue(result?.headlessSurfaceRouteAdvertised == true)
    }

    @Test
    fun missingCapabilityInSuccessfulRecordIsRejected() {
        assertNull(
            VulkanWsiCapabilityProbeParser.parse(
                valid.replace(";khr_swapchain=yes", ""),
            ),
        )
    }

    @Test
    fun invalidBooleanIsRejectedInsteadOfBecomingFalse() {
        assertNull(
            VulkanWsiCapabilityProbeParser.parse(
                valid.replace("khr_swapchain=yes", "khr_swapchain=maybe"),
            ),
        )
    }

    @Test
    fun duplicateAndUnknownFieldsAreRejected() {
        assertNull(
            VulkanWsiCapabilityProbeParser.parse(
                "$valid;vendor_id=1234",
            ),
        )
        assertNull(
            VulkanWsiCapabilityProbeParser.parse(
                "$valid;pretend_ready=yes",
            ),
        )
    }

    @Test
    fun oldProtocolMayParseButCannotBeSuccessful() {
        val result =
            VulkanWsiCapabilityProbeParser.parse(
                valid.replace("protocol=1", "protocol=0"),
            )

        assertFalse(result?.probeSucceeded == true)
        assertFalse(result?.androidSurfaceRouteAdvertised == true)
    }

    @Test
    fun extensionCountsAreBounded() {
        assertNull(
            VulkanWsiCapabilityProbeParser.parse(
                valid.replace("instance_extensions=12", "instance_extensions=999999"),
            ),
        )
    }
}
