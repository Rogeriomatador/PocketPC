package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostGraphicsResourceBrokerTest {
    @Test
    fun `create record becomes guest descriptor without exposing a handle`() {
        val record =
            HostGraphicsBrokerRecordParser.parse(
                "pocketpc-ahb-broker=create" +
                    ";status=ok" +
                    ";protocol=1" +
                    ";resource_id=42" +
                    ";generation=1" +
                    ";width=1920" +
                    ";height=1080" +
                    ";layers=1" +
                    ";pixel_format=1" +
                    ";usage=15" +
                    ";producer_pid=1000",
            )

        assertNotNull(record)
        val lease = record!!.asLeaseOrNull()
        assertNotNull(lease)

        val descriptor =
            lease!!.toGuestDescriptor(
                processNamespace = 99,
                syncSequence = 7,
            )

        assertTrue(descriptor.structurallyValid)
        assertEquals(42L, descriptor.resourceId)
        assertEquals(1L, descriptor.generation)
        assertEquals(99L, descriptor.processNamespace)
        assertEquals(7L, descriptor.syncSequence)
        assertFalse(
            GuestGraphicsResourceDescriptorCodec
                .encode(descriptor)
                .contains("fd=", ignoreCase = true),
        )
    }

    @Test
    fun `parser rejects duplicate and unknown fields`() {
        assertNull(
            HostGraphicsBrokerRecordParser.parse(
                "pocketpc-ahb-broker=create" +
                    ";status=ok;protocol=1;protocol=1",
            ),
        )
        assertNull(
            HostGraphicsBrokerRecordParser.parse(
                "pocketpc-ahb-broker=create" +
                    ";status=ok;protocol=1;raw_pointer=1234",
            ),
        )
    }

    @Test
    fun `stale send does not become a lease`() {
        val record =
            HostGraphicsBrokerRecordParser.parse(
                "pocketpc-ahb-broker=send" +
                    ";status=stale-or-unknown-resource" +
                    ";protocol=1" +
                    ";resource_id=42" +
                    ";generation=1",
            )

        assertNotNull(record)
        assertFalse(record!!.succeeded)
        assertNull(record.asLeaseOrNull())
    }

    @Test
    fun `lease rejects invalid namespace and sequence`() {
        val lease =
            HostGraphicsResourceLease(
                resourceId = 1,
                generation = 1,
                width = 64,
                height = 64,
                layers = 1,
                pixelFormat = 1,
                usage = 0,
                producerPid = 1,
            )

        assertTrue(lease.structurallyValid)

        runCatching {
            lease.toGuestDescriptor(processNamespace = 0)
        }.onSuccess {
            throw AssertionError("zero namespace should fail")
        }

        runCatching {
            lease.toGuestDescriptor(
                processNamespace = 1,
                syncSequence = -1,
            )
        }.onSuccess {
            throw AssertionError("negative sequence should fail")
        }
    }
}
