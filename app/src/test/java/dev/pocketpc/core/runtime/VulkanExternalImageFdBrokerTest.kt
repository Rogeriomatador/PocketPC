package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanExternalImageFdBrokerTest {
    private val valid =
        "vulkan-external-image-fd=ok;protocol=1;resource_id=41;generation=7;" +
            "width=64;height=64;format=37;allocation_size=16384;" +
            "memory_type_bits=5;memory_type_index=2"

    @Test
    fun validLeaseParsesAndBuildsGuestDescriptor() {
        val lease = VulkanExternalImageFdBroker.parseLease(valid)

        assertTrue(lease?.structurallyValid == true)
        assertEquals(41L, lease?.resourceId)
        assertEquals(7L, lease?.generation)
        assertEquals(5L, lease?.memoryTypeBits)
        assertEquals(2, lease?.memoryTypeIndex)

        val descriptor =
            requireNotNull(lease).toGuestDescriptor(
                producerPid = 123,
                processNamespace = 456,
                syncSequence = 9,
            )
        assertTrue(descriptor.structurallyValid)
        assertEquals(41L, descriptor.resourceId)
        assertEquals(7L, descriptor.generation)
        assertEquals(64, descriptor.width)
        assertEquals(64, descriptor.height)
        assertEquals(VulkanExternalImageFdBroker.FORMAT_R8G8B8A8_UNORM, descriptor.pixelFormat)
        assertEquals(VulkanExternalImageFdBroker.IMAGE_USAGE_FLAGS, descriptor.usage)
        assertEquals(9L, descriptor.syncSequence)
    }

    @Test
    fun memoryTypeIndexMustBeIncludedInMemoryTypeBits() {
        assertNull(
            VulkanExternalImageFdBroker.parseLease(
                valid.replace("memory_type_bits=5", "memory_type_bits=1"),
            ),
        )
    }

    @Test
    fun wrongFormatCannotBecomeLease() {
        assertNull(
            VulkanExternalImageFdBroker.parseLease(
                valid.replace("format=37", "format=44"),
            ),
        )
    }

    @Test
    fun duplicateUnknownOrMissingFieldsAreRejected() {
        assertNull(VulkanExternalImageFdBroker.parseLease("$valid;resource_id=41"))
        assertNull(VulkanExternalImageFdBroker.parseLease("$valid;raw_fd=9"))
        assertNull(
            VulkanExternalImageFdBroker.parseLease(
                valid.replace(";allocation_size=16384", ""),
            ),
        )
    }

    @Test
    fun zeroAndOutOfRangeValuesAreRejected() {
        assertNull(
            VulkanExternalImageFdBroker.parseLease(
                valid.replace("resource_id=41", "resource_id=0"),
            ),
        )
        assertNull(
            VulkanExternalImageFdBroker.parseLease(
                valid.replace("width=64", "width=5000"),
            ),
        )
        assertNull(
            VulkanExternalImageFdBroker.parseLease(
                valid.replace("allocation_size=16384", "allocation_size=0"),
            ),
        )
    }

    @Test
    fun oldProtocolCannotBecomeLease() {
        assertNull(
            VulkanExternalImageFdBroker.parseLease(
                valid.replace("protocol=1", "protocol=0"),
            ),
        )
    }

    @Test
    fun leaseContainsNoProcessLocalFileDescriptor() {
        val lease = requireNotNull(VulkanExternalImageFdBroker.parseLease(valid))
        val propertyNames =
            lease::class.java.declaredFields
                .map { it.name.lowercase() }

        assertFalse(propertyNames.any { it == "fd" || it.contains("filedescriptor") })
        assertTrue(lease.raw.contains("resource_id="))
        assertFalse(lease.raw.contains(";fd="))
    }
}
