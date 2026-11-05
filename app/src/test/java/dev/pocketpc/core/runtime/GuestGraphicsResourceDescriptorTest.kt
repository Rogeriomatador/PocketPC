package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestGraphicsResourceDescriptorTest {
    private fun valid() =
        GuestGraphicsResourceDescriptor(
            protocol = GuestGraphicsResourceDescriptorCodec.CURRENT_PROTOCOL,
            resourceId = 41L,
            generation = 3L,
            width = 1920,
            height = 1080,
            layers = 1,
            pixelFormat = 1,
            usage = 0x100L,
            producerPid = 1234,
            processNamespace = 1234L,
            syncSequence = 9L,
        )

    @Test
    fun validDescriptorRoundTripsWithoutProcessLocalHandles() {
        val encoded =
            GuestGraphicsResourceDescriptorCodec.encode(valid())
        val decoded =
            GuestGraphicsResourceDescriptorCodec.decode(encoded)

        assertEquals(valid(), decoded)
        assertFalse(encoded.contains("pointer", ignoreCase = true))
        assertFalse(encoded.contains("native_window", ignoreCase = true))
        assertFalse(encoded.contains("fd="))
    }

    @Test
    fun oldProtocolAndInvalidIdentityFailClosed() {
        assertFalse(valid().copy(protocol = 0).structurallyValid)
        assertFalse(valid().copy(resourceId = 0L).structurallyValid)
        assertFalse(valid().copy(generation = 0L).structurallyValid)
        assertFalse(valid().copy(producerPid = 0).structurallyValid)
        assertFalse(valid().copy(processNamespace = 0L).structurallyValid)
    }

    @Test
    fun unsafeDimensionsAndLayersFailClosed() {
        assertFalse(valid().copy(width = 0).structurallyValid)
        assertFalse(
            valid().copy(
                width = GuestGraphicsResourceDescriptor.MAX_DIMENSION + 1,
            ).structurallyValid,
        )
        assertFalse(
            valid().copy(
                layers = GuestGraphicsResourceDescriptor.MAX_LAYERS + 1,
            ).structurallyValid,
        )
    }

    @Test
    fun duplicateUnknownOrRawPointerFieldsAreRejected() {
        val encoded =
            GuestGraphicsResourceDescriptorCodec.encode(valid())

        assertNull(
            GuestGraphicsResourceDescriptorCodec.decode(
                "$encoded;resource_id=99",
            ),
        )
        assertNull(
            GuestGraphicsResourceDescriptorCodec.decode(
                "$encoded;raw_pointer=123456",
            ),
        )
        assertNull(
            GuestGraphicsResourceDescriptorCodec.decode(
                "$encoded;fd=7",
            ),
        )
    }

    @Test
    fun negativeUsageOrSynchronizationSequenceAreRejected() {
        assertFalse(valid().copy(usage = -1L).structurallyValid)
        assertFalse(valid().copy(syncSequence = -1L).structurallyValid)
        assertTrue(valid().structurallyValid)
    }
}
