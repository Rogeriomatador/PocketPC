package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanAhardwareBufferImportProbeTest {
    private val valid =
        "vulkan-ahb-import=ok;protocol=1;api_major=1;api_minor=3;" +
            "vendor_id=1234;device_id=5678;allocation_size=16384;" +
            "memory_type_bits=3;ahb_allocate_result=0;property_query_result=0;" +
            "vulkan_1_1_or_newer=yes;ahb_extension=yes;" +
            "foreign_queue_extension=yes;queue_family_available=yes;" +
            "device_created=yes;query_function_available=yes;ahb_allocated=yes;" +
            "properties_query_succeeded=yes;allocation_size_nonzero=yes;" +
            "memory_type_bits_nonzero=yes;canonical_import_query_supported=yes"

    @Test
    fun `canonical record requires every proof`() {
        val snapshot =
            VulkanAhardwareBufferImportProbeParser.parse(valid)

        assertTrue(snapshot?.probeSucceeded == true)
        assertTrue(snapshot?.canonicalImportQuerySupported == true)
    }

    @Test
    fun `native claim alone cannot promote canonical support`() {
        val snapshot =
            VulkanAhardwareBufferImportProbeParser.parse(
                valid.replace("memory_type_bits_nonzero=yes", "memory_type_bits_nonzero=no"),
            )

        assertTrue(snapshot?.probeSucceeded == true)
        assertFalse(snapshot?.canonicalImportQuerySupported == true)
    }

    @Test
    fun `zero allocation or memory type bits is rejected as capability`() {
        val zeroAllocation =
            VulkanAhardwareBufferImportProbeParser.parse(
                valid.replace("allocation_size=16384", "allocation_size=0"),
            )
        val zeroBits =
            VulkanAhardwareBufferImportProbeParser.parse(
                valid.replace("memory_type_bits=3", "memory_type_bits=0"),
            )

        assertFalse(zeroAllocation?.canonicalImportQuerySupported == true)
        assertFalse(zeroBits?.canonicalImportQuerySupported == true)
    }

    @Test
    fun `old protocol cannot become successful capability`() {
        val snapshot =
            VulkanAhardwareBufferImportProbeParser.parse(
                valid.replace("protocol=1", "protocol=0"),
            )

        assertFalse(snapshot?.probeSucceeded == true)
        assertFalse(snapshot?.canonicalImportQuerySupported == true)
    }

    @Test
    fun `malformed booleans duplicates and unknown fields are rejected`() {
        assertNull(
            VulkanAhardwareBufferImportProbeParser.parse(
                valid.replace("ahb_allocated=yes", "ahb_allocated=maybe"),
            ),
        )
        assertNull(
            VulkanAhardwareBufferImportProbeParser.parse(
                "$valid;vendor_id=1234",
            ),
        )
        assertNull(
            VulkanAhardwareBufferImportProbeParser.parse(
                "$valid;pretend_roblox_ready=yes",
            ),
        )
    }

    @Test
    fun `successful record missing required field is rejected`() {
        assertNull(
            VulkanAhardwareBufferImportProbeParser.parse(
                valid.replace(";property_query_result=0", ""),
            ),
        )
        assertNull(
            VulkanAhardwareBufferImportProbeParser.parse(
                valid.replace(";query_function_available=yes", ""),
            ),
        )
    }
}
