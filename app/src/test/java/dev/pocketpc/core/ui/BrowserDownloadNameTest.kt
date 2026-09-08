package dev.pocketpc.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserDownloadNameTest {
    @Test
    fun decodesRfc5987Utf8Filename() {
        assertEquals(
            "Roblox Player Installer.exe",
            contentDispositionFileName(
                "attachment; " +
                    "filename*=UTF-8''Roblox%20Player%20Installer.exe"
            ),
        )
    }

    @Test
    fun readsQuotedClassicFilename() {
        assertEquals(
            "setup.exe",
            contentDispositionFileName(
                "attachment; filename=\"setup.exe\""
            ),
        )
    }

    @Test
    fun acceptsObservedAndroidFilenameUnderscoreVariant() {
        assertEquals(
            "RobloxPlayerInstaller.exe",
            contentDispositionFileName(
                "attachment; " +
                    "filename_=UTF-8''RobloxPlayerInstaller.exe"
            ),
        )
    }

    @Test
    fun absentFilenameReturnsNull() {
        assertEquals(
            null,
            contentDispositionFileName(
                "attachment"
            ),
        )
    }
}
