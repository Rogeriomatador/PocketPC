package dev.pocketpc.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatBytesTest {
    @Test
    fun formatsHumanReadableSizes() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.0 MB", formatBytes(1024L * 1024L))
        assertEquals("1.0 GB", formatBytes(1024L * 1024L * 1024L))
    }
}
