package dev.pocketpc.core.storage

private const val ISO9660_DESCRIPTOR_BYTES = 2048
private const val ISO9660_PRIMARY_TYPE = 1
private const val ISO9660_VERSION = 1

data class PocketIso9660PrimaryDescriptor(
    val volumeId: String,
    val logicalBlockSize: Int,
    val volumeBlocks: Long,
    val declaredBytes: Long,
)

/**
 * Parses the ISO9660 Primary Volume Descriptor without touching Android APIs.
 *
 * ISO9660 stores important numeric fields twice (little-endian followed by
 * big-endian). PocketPC requires both copies to agree so a malformed image
 * cannot choose whichever interpretation is more convenient.
 */
internal fun parsePocketIso9660PrimaryDescriptor(
    descriptor: ByteArray,
): PocketIso9660PrimaryDescriptor {
    require(descriptor.size == ISO9660_DESCRIPTOR_BYTES) {
        "Descritor ISO9660 deve possuir exatamente 2048 bytes."
    }

    val type = descriptor[0].toInt() and 0xFF
    require(type == ISO9660_PRIMARY_TYPE) {
        "O descritor informado não é um Primary Volume Descriptor ISO9660."
    }

    val signature =
        descriptor.copyOfRange(1, 6)
            .toString(Charsets.US_ASCII)
    require(signature == "CD001") {
        "Assinatura ISO9660 inválida."
    }

    val version = descriptor[6].toInt() and 0xFF
    require(version == ISO9660_VERSION) {
        "Versão ISO9660 não suportada: $version."
    }

    val volumeBlocksLittle = readUInt32LittleEndian(descriptor, 80)
    val volumeBlocksBig = readUInt32BigEndian(descriptor, 84)
    require(volumeBlocksLittle == volumeBlocksBig) {
        "ISO9660 possui cópias endian divergentes do tamanho do volume."
    }

    val logicalBlockLittle = readUInt16LittleEndian(descriptor, 128)
    val logicalBlockBig = readUInt16BigEndian(descriptor, 130)
    require(logicalBlockLittle == logicalBlockBig) {
        "ISO9660 possui cópias endian divergentes do tamanho de bloco."
    }
    require(logicalBlockLittle > 0) {
        "ISO9660 declarou tamanho de bloco inválido."
    }
    require(
        volumeBlocksLittle <= Long.MAX_VALUE / logicalBlockLittle.toLong()
    ) {
        "ISO9660 declarou tamanho de volume fora do intervalo suportado."
    }

    val volumeId =
        descriptor.copyOfRange(40, 72)
            .toString(Charsets.US_ASCII)
            .trim('\u0000', ' ')

    return PocketIso9660PrimaryDescriptor(
        volumeId = volumeId,
        logicalBlockSize = logicalBlockLittle,
        volumeBlocks = volumeBlocksLittle,
        declaredBytes = volumeBlocksLittle * logicalBlockLittle.toLong(),
    )
}

private fun readUInt16LittleEndian(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8)

private fun readUInt16BigEndian(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 8) or
        (bytes[offset + 1].toInt() and 0xFF)

private fun readUInt32LittleEndian(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xFFL) or
        ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFFL) shl 24)

private fun readUInt32BigEndian(bytes: ByteArray, offset: Int): Long =
    ((bytes[offset].toLong() and 0xFFL) shl 24) or
        ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
        ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
        (bytes[offset + 3].toLong() and 0xFFL)
