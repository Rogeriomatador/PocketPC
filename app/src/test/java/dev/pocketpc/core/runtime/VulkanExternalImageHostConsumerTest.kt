package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanExternalImageHostConsumerTest {
    private val lease =
        VulkanExternalImageFdLease(
            protocol = VulkanExternalImageFdBroker.PROTOCOL_VERSION,
            resourceId = 41L,
            generation = 7L,
            width = 64,
            height = 32,
            format = VulkanExternalImageFdBroker.FORMAT_R8G8B8A8_UNORM,
            allocationSize = 65_536L,
            memoryTypeBits = 0x5L,
            memoryTypeIndex = 2,
            externalOwner = true,
            boundaryLayout = VulkanExternalImageFdBroker.EXTERNAL_BOUNDARY_LAYOUT_GENERAL,
            raw = "fixture",
        )

    @Test
    fun successfulReadbackProvesAcquireButNotVisibleFrame() {
        val raw =
            "vulkan-external-image-host-consume=ok;protocol=1;resource_id=41;generation=7;" +
                "sequence=1;timeline_value=1;bytes=8192;nonzero_bytes=4096;" +
                "fnv1a64=123456789;acquired=1;returned_external=1;visible_frame=0"

        val result = VulkanExternalImageHostConsumer.parseReadback(raw, lease, 1L)

        assertNotNull(result)
        requireNotNull(result)
        assertTrue(result.structurallyValid)
        assertTrue(result.androidAcquireExecuted)
        assertFalse(result.androidVisibleFrame)
        assertEquals(8192L, result.bytes)
        assertEquals(4096L, result.nonzeroBytes)
    }

    @Test
    fun visibleFrameClaimIsRejectedByReadbackGate() {
        val raw =
            "vulkan-external-image-host-consume=ok;protocol=1;resource_id=41;generation=7;" +
                "sequence=1;timeline_value=1;bytes=8192;nonzero_bytes=0;" +
                "fnv1a64=1;acquired=1;returned_external=1;visible_frame=1"

        assertNull(VulkanExternalImageHostConsumer.parseReadback(raw, lease, 1L))
    }

    @Test
    fun mismatchedIdentityOrByteCountIsRejected() {
        val wrongIdentity =
            "vulkan-external-image-host-consume=ok;protocol=1;resource_id=42;generation=7;" +
                "sequence=1;timeline_value=1;bytes=8192;nonzero_bytes=0;" +
                "fnv1a64=1;acquired=1;returned_external=1;visible_frame=0"
        val wrongBytes =
            "vulkan-external-image-host-consume=ok;protocol=1;resource_id=41;generation=7;" +
                "sequence=1;timeline_value=1;bytes=4096;nonzero_bytes=0;" +
                "fnv1a64=1;acquired=1;returned_external=1;visible_frame=0"

        assertNull(VulkanExternalImageHostConsumer.parseReadback(wrongIdentity, lease, 1L))
        assertNull(VulkanExternalImageHostConsumer.parseReadback(wrongBytes, lease, 1L))
    }
}
