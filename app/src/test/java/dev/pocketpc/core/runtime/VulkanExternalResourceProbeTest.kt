package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanExternalResourceProbeTest {
    private val valid =
        "vulkan-external-resource=ok;protocol=1;vendor_id=1234;device_id=5678;" +
            "instance_extensions=12;device_extensions=34;" +
            "khr_external_memory_capabilities=yes;" +
            "khr_external_memory=yes;khr_external_memory_fd=yes;" +
            "ext_external_memory_dma_buf=yes;android_external_memory_ahb=yes;" +
            "khr_external_semaphore=yes;khr_external_semaphore_fd=yes;" +
            "khr_external_fence=yes;khr_external_fence_fd=yes;" +
            "query_external_buffer_properties=yes;" +
            "opaque_fd_queried=yes;opaque_fd_importable=yes;opaque_fd_exportable=yes;" +
            "ahb_queried=yes;ahb_importable=yes;ahb_exportable=yes;" +
            "dma_buf_queried=yes;dma_buf_importable=yes;dma_buf_exportable=yes"

    @Test
    fun `complete fd route is advertised without promoting guest transport`() {
        val result = VulkanExternalResourceProbeParser.parse(valid)

        assertTrue(result?.probeSucceeded == true)
        assertTrue(result?.opaqueFdMemoryRouteAdvertised == true)
        assertTrue(result?.dmaBufMemoryRouteAdvertised == true)
        assertTrue(result?.ahardwareBufferMemoryRouteAdvertised == true)
        assertTrue(result?.fdSynchronizationRouteAdvertised == true)
        assertTrue(result?.potentialGuestFdTransportRoute == true)
        assertFalse(GuestGraphicsTransportContract.guestReceiveImplemented)
        assertFalse(GuestGraphicsTransportContract.guestImportImplemented)
        assertFalse(GuestGraphicsTransportContract.synchronizationImplemented)
    }

    @Test
    fun `extension advertisement without import capability stays blocked`() {
        val result =
            VulkanExternalResourceProbeParser.parse(
                valid
                    .replace("opaque_fd_importable=yes", "opaque_fd_importable=no")
                    .replace("dma_buf_importable=yes", "dma_buf_importable=no")
                    .replace("ahb_importable=yes", "ahb_importable=no"),
            )

        assertTrue(result?.probeSucceeded == true)
        assertFalse(result?.opaqueFdMemoryRouteAdvertised == true)
        assertFalse(result?.dmaBufMemoryRouteAdvertised == true)
        assertFalse(result?.ahardwareBufferMemoryRouteAdvertised == true)
        assertFalse(result?.potentialGuestFdTransportRoute == true)
    }

    @Test
    fun `memory route without fd synchronization stays blocked`() {
        val result =
            VulkanExternalResourceProbeParser.parse(
                valid.replace(
                    "khr_external_semaphore_fd=yes",
                    "khr_external_semaphore_fd=no",
                ),
            )

        assertTrue(result?.opaqueFdMemoryRouteAdvertised == true)
        assertFalse(result?.fdSynchronizationRouteAdvertised == true)
        assertFalse(result?.potentialGuestFdTransportRoute == true)
    }

    @Test
    fun `successful record missing a required capability is rejected`() {
        assertNull(
            VulkanExternalResourceProbeParser.parse(
                valid.replace(";ahb_importable=yes", ""),
            ),
        )
    }

    @Test
    fun `unknown duplicate and malformed fields are rejected`() {
        assertNull(
            VulkanExternalResourceProbeParser.parse(
                "$valid;vendor_id=1234",
            ),
        )
        assertNull(
            VulkanExternalResourceProbeParser.parse(
                "$valid;pretend_guest_ready=yes",
            ),
        )
        assertNull(
            VulkanExternalResourceProbeParser.parse(
                valid.replace(
                    "opaque_fd_importable=yes",
                    "opaque_fd_importable=maybe",
                ),
            ),
        )
    }

    @Test
    fun `old protocol parses but cannot become a successful probe`() {
        val result =
            VulkanExternalResourceProbeParser.parse(
                valid.replace("protocol=1", "protocol=0"),
            )

        assertFalse(result?.probeSucceeded == true)
        assertFalse(result?.potentialGuestFdTransportRoute == true)
    }
}
