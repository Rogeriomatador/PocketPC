package dev.pocketpc.core.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageNameValidationTest {
    @Test
    fun trimsValidNames() {
        assertEquals(
            "Projetos",
            validateStorageName("  Projetos  "),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyName() {
        validateStorageName("   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathSeparator() {
        validateStorageName("pasta/filho")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsParentTraversalName() {
        validateStorageName("..")
    }

    @Test
    fun sanitizesServerFileNamesForPocketDrive() {
        assertEquals(
            "setup_invalid_.exe",
            sanitizePocketImportedFileName(
                "../setup:invalid?.exe"
            ),
        )
    }

    @Test
    fun prefixesWindowsReservedDeviceNames() {
        assertEquals(
            "_CON.txt",
            sanitizePocketImportedFileName(
                "CON.txt"
            ),
        )
    }

    @Test
    fun emptyImportedNameGetsStableFallback() {
        assertEquals(
            "download",
            sanitizePocketImportedFileName(
                "..."
            ),
        )
    }
}
