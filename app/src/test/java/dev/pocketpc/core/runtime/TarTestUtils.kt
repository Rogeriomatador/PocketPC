package dev.pocketpc.core.runtime

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

internal data class TestTarEntry(
    val path: String,
    val type: Char = '0',
    val data: ByteArray = ByteArray(0),
    val linkTarget: String = "",
)

internal object TarTestFactory {
    fun build(vararg entries: TestTarEntry): ByteArray {
        val output = ByteArrayOutputStream()
        entries.forEach { entry ->
            val header = ByteArray(512)
            putString(header, 0, 100, entry.path)
            putOctal(header, 100, 8, if (entry.type == '5') 493 else 420)
            putOctal(header, 108, 8, 0)
            putOctal(header, 116, 8, 0)
            putOctal(header, 124, 12, entry.data.size.toLong())
            putOctal(header, 136, 12, 0)
            for (index in 148..155) header[index] = ' '.code.toByte()
            header[156] = entry.type.code.toByte()
            putString(header, 157, 100, entry.linkTarget)
            putString(header, 257, 6, "ustar")
            putString(header, 263, 2, "00")

            val checksum = header.sumOf { it.toInt() and 0xff }
            val checksumText = checksum.toString(8).padStart(6, '0') + "\u0000 "
            putStringRaw(header, 148, 8, checksumText)

            output.write(header)
            output.write(entry.data)
            val padding = (512 - (entry.data.size % 512)) % 512
            if (padding > 0) output.write(ByteArray(padding))
        }
        output.write(ByteArray(1024))
        return output.toByteArray()
    }

    private fun putOctal(
        target: ByteArray,
        offset: Int,
        length: Int,
        value: Long,
    ) {
        val text = value.toString(8).padStart(length - 1, '0') + "\u0000"
        putStringRaw(target, offset, length, text)
    }

    private fun putString(
        target: ByteArray,
        offset: Int,
        length: Int,
        value: String,
    ) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= length)
        bytes.copyInto(target, offset)
    }

    private fun putStringRaw(
        target: ByteArray,
        offset: Int,
        length: Int,
        value: String,
    ) {
        val bytes = value.toByteArray(StandardCharsets.US_ASCII)
        require(bytes.size == length)
        bytes.copyInto(target, offset)
    }
}
