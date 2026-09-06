package dev.pocketpc.core.runtime

import java.io.InputStream
import java.security.MessageDigest

data class DigestResult(
    val sha256: String,
    val bytes: Long,
)

object Sha256 {
    fun digest(
        input: InputStream,
        maxBytes: Long = Long.MAX_VALUE,
        onChunk: ((Long) -> Unit)? = null,
    ): DigestResult {
        require(maxBytes > 0L) { "maxBytes precisa ser positivo." }

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L

        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw IllegalArgumentException("Entrada excedeu o limite declarado.")
            }
            digest.update(buffer, 0, read)
            onChunk?.invoke(total)
        }

        return DigestResult(
            sha256 = digest.digest().toHex(),
            bytes = total,
        )
    }
}

internal fun ByteArray.toHex(): String =
    joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
