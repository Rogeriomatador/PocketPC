package dev.pocketpc.core.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class PocketDownloadBridgeTest {
    @Test
    fun `repairs generic bin name when payload has MZ signature`() {
        assertEquals(
            "winrar-x64-723br.exe",
            normalizeDownloadedPayloadName(
                title = "winrar-x64-723br.bin",
                mimeType = "application/octet-stream",
                firstByte = 0x4d,
                secondByte = 0x5a,
            ),
        )
    }

    @Test
    fun `does not relabel arbitrary bin payload`() {
        assertEquals(
            "firmware.bin",
            normalizeDownloadedPayloadName(
                title = "firmware.bin",
                mimeType = "application/octet-stream",
                firstByte = 0x00,
                secondByte = 0x01,
            ),
        )
    }

    @Test
    fun `does not change already executable name`() {
        assertEquals(
            "setup.exe",
            normalizeDownloadedPayloadName(
                title = "setup.exe",
                mimeType = "application/x-msdownload",
                firstByte = 0x4d,
                secondByte = 0x5a,
            ),
        )
    }
}
