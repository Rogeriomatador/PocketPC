package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WindowsPrefixLayoutTest {
    @Test
    fun plansPrefixInsidePocketRuntimeHome() {
        val root = Files.createTempDirectory("pocketpc-wine-prefix-").toFile()
        try {
            val result = WindowsPrefixPlanner.plan(root, "default")
            assertTrue(result.blockers.joinToString(), result.valid)
            val layout = assertNotNull(result.layout)
            assertEquals(
                "/home/pocket/windows-prefixes/default",
                layout.guestPrefixRoot,
            )
            assertEquals("drive_c", layout.driveC.name)
            assertEquals("system.reg", layout.systemRegistry.name)
            assertEquals("user.reg", layout.userRegistry.name)
            assertEquals("userdef.reg", layout.userDefRegistry.name)
            assertTrue(
                layout.prefixRoot.canonicalPath.startsWith(
                    root.canonicalPath + java.io.File.separator
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsProfileTraversalAndSeparators() {
        val root = Files.createTempDirectory("pocketpc-wine-prefix-reject-").toFile()
        try {
            listOf("../escape", "a/b", "a\\b", "", ".").forEach { id ->
                val result = WindowsPrefixPlanner.plan(root, id)
                assertFalse(id, result.valid)
                assertTrue(
                    id,
                    result.blockers.contains("WINDOWS_PREFIX_PROFILE_ID_INVALID"),
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
