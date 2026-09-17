package dev.pocketpc.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketIso9660Test {
    @Test
    fun primaryDescriptorParsesMatchingBothEndianFields() {
        val descriptor = validDescriptor(
            volumeId = "POCKETPC_TEST",
            volumeBlocks = 12345L,
            logicalBlockSize = 2048,
        )

        val parsed = parsePocketIso9660PrimaryDescriptor(descriptor)

        assertEquals("POCKETPC_TEST", parsed.volumeId)
        assertEquals(12345L, parsed.volumeBlocks)
        assertEquals(2048, parsed.logicalBlockSize)
        assertEquals(12345L * 2048L, parsed.declaredBytes)
    }

    @Test
    fun badSignatureIsRejected() {
        val descriptor = validDescriptor()
        descriptor[1] = 'X'.code.toByte()
        assertRejected(descriptor)
    }

    @Test
    fun nonPrimaryDescriptorIsRejected() {
        val descriptor = validDescriptor()
        descriptor[0] = 2
        assertRejected(descriptor)
    }

    @Test
    fun divergentVolumeSizeEndianCopiesAreRejected() {
        val descriptor = validDescriptor(volumeBlocks = 100L)
        writeUInt32BigEndian(descriptor, 84, 101L)
        assertRejected(descriptor)
    }

    @Test
    fun divergentLogicalBlockEndianCopiesAreRejected() {
        val descriptor = validDescriptor(logicalBlockSize = 2048)
        writeUInt16BigEndian(descriptor, 130, 1024)
        assertRejected(descriptor)
    }

    @Test
    fun zeroLogicalBlockSizeIsRejected() {
        val descriptor = validDescriptor(logicalBlockSize = 0)
        assertRejected(descriptor)
    }

    private fun assertRejected(descriptor: ByteArray) {
        val result =
            runCatching {
                parsePocketIso9660PrimaryDescriptor(descriptor)
            }
        assertTrue(result.isFailure)
    }

    private fun validDescriptor(
        volumeId: String = "POCKETPC",
        volumeBlocks: Long = 2048L,
        logicalBlockSize: Int = 2048,
    ): ByteArray {
        val descriptor = ByteArray(2048)
        descriptor[0] = 1
        "CD001".toByteArray(Charsets.US_ASCII)
            .copyInto(descriptor, destinationOffset = 1)
        descriptor[6] = 1

        volumeId.padEnd(32, ' ')
            .take(32)
            .toByteArray(Charsets.US_ASCII)
            .copyInto(descriptor, destinationOffset = 40)

        writeUInt32LittleEndian(descriptor, 80, volumeBlocks)
        writeUInt32BigEndian(descriptor, 84, volumeBlocks)
        writeUInt16LittleEndian(descriptor, 128, logicalBlockSize)
        writeUInt16BigEndian(descriptor, 130, logicalBlockSize)
        return descriptor
    }

    private fun writeUInt16LittleEndian(
        target: ByteArray,
        offset: Int,
        value: Int,
    ) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeUInt16BigEndian(
        target: ByteArray,
        offset: Int,
        value: Int,
    ) {
        target[offset] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeUInt32LittleEndian(
        target: ByteArray,
        offset: Int,
        value: Long,
    ) {
        for (index in 0..3) {
            target[offset + index] =
                ((value ushr (index * 8)) and 0xFFL).toByte()
        }
    }

    private fun writeUInt32BigEndian(
        target: ByteArray,
        offset: Int,
        value: Long,
    ) {
        for (index in 0..3) {
            target[offset + index] =
                ((value ushr ((3 - index) * 8)) and 0xFFL).toByte()
        }
    }
}
