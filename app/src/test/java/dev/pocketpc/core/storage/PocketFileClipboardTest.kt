package dev.pocketpc.core.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PocketFileClipboardTest {
    @After
    fun cleanup() {
        PocketFileClipboard.clear()
    }

    @Test
    fun copyOwnsEntryInsidePocketPcClipboard() {
        val entry = sampleEntry("arquivo.txt")

        val item = PocketFileClipboard.copy(entry)

        assertEquals(PocketClipboardOperation.COPY, item.operation)
        assertEquals(entry, item.entry)
        assertEquals(item, PocketFileClipboard.item.value)
    }

    @Test
    fun cutReplacesPriorClipboardOperation() {
        PocketFileClipboard.copy(sampleEntry("primeiro.txt"))
        val entry = sampleEntry("segundo.txt")

        val item = PocketFileClipboard.cut(entry)

        assertEquals(PocketClipboardOperation.CUT, item.operation)
        assertEquals(entry, item.entry)
        assertEquals(item, PocketFileClipboard.item.value)
    }

    @Test
    fun clearIfSameDoesNotEraseNewerClipboardItem() {
        val old = PocketFileClipboard.copy(sampleEntry("antigo.txt"))
        val newer = PocketFileClipboard.copy(sampleEntry("novo.txt"))

        PocketFileClipboard.clearIfSame(old)

        assertEquals(newer, PocketFileClipboard.item.value)
    }

    @Test
    fun clearIfSameClearsMatchingItem() {
        val item = PocketFileClipboard.copy(sampleEntry("arquivo.txt"))

        PocketFileClipboard.clearIfSame(item)

        assertNull(PocketFileClipboard.item.value)
    }

    private fun sampleEntry(name: String) =
        StorageEntry(
            name = name,
            uri = "content://pocketpc-test/$name",
            directory = false,
            size = 42L,
            mimeType = "text/plain",
            lastModified = 123L,
        )
}
