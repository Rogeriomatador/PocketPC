package dev.pocketpc.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketTarArchiveTest {
    @Test
    fun validRelativeTarPathIsPreserved() {
        assertEquals(
            listOf("projeto", "assets", "icone.png"),
            validatePocketTarEntryPath("projeto/assets/icone.png"),
        )
    }

    @Test
    fun windowsSeparatorsAreNormalizedInsideRelativePath() {
        assertEquals(
            listOf("projeto", "dados", "config.json"),
            validatePocketTarEntryPath("projeto\\dados\\config.json"),
        )
    }

    @Test
    fun parentTraversalIsRejected() {
        assertRejected("../fora.txt")
        assertRejected("pasta/../../fora.txt")
        assertRejected("pasta/../fora.txt")
    }

    @Test
    fun absolutePathsAreRejected() {
        assertRejected("/etc/passwd")
        assertRejected("C:\\Windows\\system.ini")
        assertRejected("D:/dados/arquivo.txt")
    }

    @Test
    fun windowsReservedNamesAreRejected() {
        assertRejected("pasta/CON")
        assertRejected("pasta/nul.txt")
        assertRejected("COM1.log")
        assertRejected("LPT9")
    }

    @Test
    fun incompatibleWindowsNamesAreRejected() {
        assertRejected("pasta/arquivo:stream.txt")
        assertRejected("pasta/arquivo?.txt")
        assertRejected("pasta/<arquivo>.txt")
        assertRejected("pasta/final. ")
    }

    @Test
    fun excessiveDepthIsRejected() {
        val path =
            (1..33).joinToString("/") { "nivel$it" } + "/arquivo.txt"
        assertRejected(path)
    }

    private fun assertRejected(path: String) {
        val result = runCatching { validatePocketTarEntryPath(path) }
        assertTrue("Expected TAR path to be rejected: $path", result.isFailure)
    }
}
