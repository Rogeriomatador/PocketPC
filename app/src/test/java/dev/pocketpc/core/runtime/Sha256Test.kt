package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class Sha256Test {
    @Test
    fun computesKnownDigestAndByteCount() {
        val result = Sha256.digest(ByteArrayInputStream("PocketPC".toByteArray()))

        assertEquals(8L, result.bytes)
        assertEquals(
            "1f326291379b266fdf3033c400061d5b6a9ff5d0658da76a4894e854627ec304",
            result.sha256,
        )
    }

    @Test
    fun enforcesMaximumBytes() {
        assertThrows(IllegalArgumentException::class.java) {
            Sha256.digest(ByteArrayInputStream(ByteArray(9)), maxBytes = 8)
        }
    }
}
