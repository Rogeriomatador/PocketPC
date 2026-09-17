package dev.pocketpc.core.runtime

import java.io.RandomAccessFile

data class RuntimeDisplayFramePixels(
    val width: Int,
    val height: Int,
    val argb: IntArray,
)

object RuntimeDisplayFrameReader {
    fun read(
        framebuffer:
            RuntimeDisplaySharedFramebuffer,
    ): Result<RuntimeDisplayFramePixels> =
        runCatching {
            val descriptor =
                framebuffer.descriptor
            val surface =
                descriptor.surface
            require(
                descriptor.hostFile
                    .length() ==
                    descriptor.bytes,
            ) {
                "DISPLAY_FRAME_SIZE_CHANGED"
            }

            val pixelCount =
                Math.multiplyExact(
                    surface.width,
                    surface.height,
                )
            val pixels =
                IntArray(pixelCount)

            RandomAccessFile(
                descriptor.hostFile,
                "r",
            ).use { file ->
                val row =
                    ByteArray(
                        surface.strideBytes,
                    )
                var destination = 0

                for (
                    y in
                    0 until surface.height
                ) {
                    file.readFully(row)

                    for (
                        x in
                        0 until surface.width
                    ) {
                        val offset =
                            x * 4
                        val b =
                            row[offset]
                                .toInt() and
                                0xff
                        val g =
                            row[
                                offset + 1
                            ].toInt() and
                                0xff
                        val r =
                            row[
                                offset + 2
                            ].toInt() and
                                0xff
                        val a =
                            row[
                                offset + 3
                            ].toInt() and
                                0xff

                        pixels[
                            destination++
                        ] =
                            (
                                a shl 24
                            ) or
                                (
                                    r shl 16
                                ) or
                                (
                                    g shl 8
                                ) or
                                b
                    }
                }

                require(
                    destination ==
                        pixelCount,
                ) {
                    "DISPLAY_FRAME_PIXEL_COUNT_MISMATCH"
                }
                require(
                    file.filePointer ==
                        descriptor.bytes,
                ) {
                    "DISPLAY_FRAME_READ_SIZE_MISMATCH"
                }
            }

            RuntimeDisplayFramePixels(
                width =
                    surface.width,
                height =
                    surface.height,
                argb = pixels,
            )
        }
}
