package dev.pocketpc.core.runtime

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

internal object RootfsMetadataTestUtils {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun write(file: File, entries: List<RootfsMetadataEntry>) {
        file.parentFile?.mkdirs()
        file.writeText(
            entries.joinToString(separator = "\n", postfix = if (entries.isEmpty()) "" else "\n") { entry ->
                listOf(
                    when (entry.type) {
                        RootfsEntryType.FILE -> "F"
                        RootfsEntryType.DIRECTORY -> "D"
                        RootfsEntryType.SYMLINK -> "S"
                        RootfsEntryType.HARDLINK -> "H"
                    },
                    entry.mode.toString(),
                    entry.uid.toString(),
                    entry.gid.toString(),
                    entry.size.toString(),
                    entry.mtime.toString(),
                    encode(entry.path),
                    encode(entry.target),
                ).joinToString("\t")
            }
        )
    }

    private fun encode(value: String): String =
        encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}
