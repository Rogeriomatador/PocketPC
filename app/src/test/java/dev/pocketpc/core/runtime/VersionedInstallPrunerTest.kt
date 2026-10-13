package dev.pocketpc.core.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionedInstallPrunerTest {
    @Test
    fun removesOnlySupersededVersionsForSelectedComponent() {
        val root =
            Files.createTempDirectory(
                "pocketpc-pruner-",
            ).toFile()
        try {
            val selected = File(root, "wine").apply { mkdirs() }
            val old = File(selected, "10.0").apply { mkdirs() }
            val keep = File(selected, "11.0").apply { mkdirs() }
            val hidden = File(selected, ".tmp-tool-11.0").apply { mkdirs() }
            val other =
                File(root, "box64/0.4.4").apply {
                    mkdirs()
                }
            File(old, "old.bin").writeText("old")
            File(keep, "current.bin").writeText("current")

            val result =
                VersionedInstallPruner.prune(
                    containerRoot = root,
                    componentId = "wine",
                    keepVersion = "11.0",
                )

            assertEquals(listOf("10.0"), result.removedVersions)
            assertTrue(result.failedVersions.isEmpty())
            assertFalse(old.exists())
            assertTrue(keep.isDirectory)
            assertTrue(File(keep, "current.bin").isFile)
            assertTrue(hidden.isDirectory)
            assertTrue(other.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsPathSegmentsThatCouldEscapeOwnedRoot() {
        val root =
            Files.createTempDirectory(
                "pocketpc-pruner-segment-",
            ).toFile()
        try {
            val componentEscape =
                runCatching {
                    VersionedInstallPruner.prune(
                        containerRoot = root,
                        componentId = "../outside",
                        keepVersion = "1",
                    )
                }
            val versionEscape =
                runCatching {
                    VersionedInstallPruner.prune(
                        containerRoot = root,
                        componentId = "wine",
                        keepVersion = "../outside",
                    )
                }

            assertTrue(componentEscape.isFailure)
            assertTrue(versionEscape.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }
}
