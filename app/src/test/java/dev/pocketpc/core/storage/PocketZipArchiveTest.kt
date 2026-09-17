package dev.pocketpc.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketZipArchiveTest {
    @Test
    fun validRelativeZipPathIsPreserved() {
        assertEquals(
            listOf("projeto", "assets", "icone.png"),
            validatePocketZipEntryPath(
                "projeto/assets/icone.png"
            ),
        )
    }

    @Test
    fun windowsSeparatorsInsideRelativePathAreNormalized() {
        assertEquals(
            listOf("projeto", "dados", "config.json"),
            validatePocketZipEntryPath(
                "projeto\\dados\\config.json"
            ),
        )
    }

    @Test
    fun zipSlipParentTraversalIsRejected() {
        assertRejected("../fora.txt")
        assertRejected("pasta/../../fora.txt")
        assertRejected("pasta/../fora.txt")
    }

    @Test
    fun absoluteUnixAndWindowsPathsAreRejected() {
        assertRejected("/etc/passwd")
        assertRejected("C:\\Windows\\system.ini")
        assertRejected("D:/dados/arquivo.txt")
    }

    @Test
    fun incompatibleWindowsNamesAreRejected() {
        assertRejected("pasta/arquivo:alternativo.txt")
        assertRejected("pasta/arquivo?.txt")
        assertRejected("pasta/<arquivo>.txt")
        assertRejected("pasta/nome. ")
    }

    @Test
    fun windowsReservedDeviceNamesAreRejected() {
        assertRejected("CON")
        assertRejected("nul.txt")
        assertRejected("pasta/COM1.log")
        assertRejected("pasta/lpt9")
    }

    @Test
    fun excessiveDepthIsRejected() {
        val path =
            (1..33)
                .joinToString("/") { "nivel$it" } +
                "/arquivo.txt"
        assertRejected(path)
    }

    private fun assertRejected(path: String) {
        val result =
            runCatching {
                validatePocketZipEntryPath(path)
            }
        assertTrue(
            "Expected ZIP path to be rejected: $path",
            result.isFailure,
        )
    }
}
