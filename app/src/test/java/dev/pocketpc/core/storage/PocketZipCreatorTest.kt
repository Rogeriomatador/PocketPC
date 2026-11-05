package dev.pocketpc.core.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class PocketZipCreatorTest {
    @Test
    fun fileGetsZipSuffix() {
        assertEquals(
            "projeto.txt.zip",
            pocketZipOutputName("projeto.txt"),
        )
    }

    @Test
    fun existingZipNameDoesNotBecomeZipZip() {
        assertEquals(
            "backup.zip",
            pocketZipOutputName("backup.zip"),
        )
    }

    @Test
    fun unsafeWindowsNameIsSanitizedBeforeZipCreation() {
        assertEquals(
            "_CON.zip",
            pocketZipOutputName("CON"),
        )
    }

    @Test
    fun pathFragmentsCannotEscapeOutputDirectory() {
        assertEquals(
            "arquivo.txt.zip",
            pocketZipOutputName("../../arquivo.txt"),
        )
    }
}
