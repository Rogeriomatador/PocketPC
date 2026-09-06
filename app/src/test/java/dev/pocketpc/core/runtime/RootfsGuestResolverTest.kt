package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RootfsGuestResolverTest {
    @Test
    fun resolvesRelativeDirectorySymlinkInsideGuestRoot() {
        val base = Files.createTempDirectory("pocketpc-resolver-").toFile()
        try {
            val root = base.resolve("rootfs").apply { mkdirs() }
            root.resolve("usr/bin").mkdirs()
            root.resolve("usr/bin/sh").writeText("guest")

            val metadata = listOf(
                RootfsMetadataEntry(
                    type = RootfsEntryType.DIRECTORY,
                    mode = 493,
                    uid = 0,
                    gid = 0,
                    size = 0,
                    mtime = 0,
                    path = "usr",
                    target = "",
                ),
                RootfsMetadataEntry(
                    type = RootfsEntryType.DIRECTORY,
                    mode = 493,
                    uid = 0,
                    gid = 0,
                    size = 0,
                    mtime = 0,
                    path = "usr/bin",
                    target = "",
                ),
                RootfsMetadataEntry(
                    type = RootfsEntryType.FILE,
                    mode = 493,
                    uid = 0,
                    gid = 0,
                    size = 5,
                    mtime = 0,
                    path = "usr/bin/sh",
                    target = "",
                ),
                RootfsMetadataEntry(
                    type = RootfsEntryType.SYMLINK,
                    mode = 511,
                    uid = 0,
                    gid = 0,
                    size = 0,
                    mtime = 0,
                    path = "bin",
                    target = "usr/bin",
                ),
            )

            val result = RootfsGuestResolver.resolve(root, metadata, "/bin/sh")

            assertNull(result.error)
            assertEquals("/usr/bin/sh", result.resolvedGuestPath)
            assertEquals(root.resolve("usr/bin/sh").toPath().toAbsolutePath().normalize(), result.hostPath)
            assertEquals(1, result.linkHops)
            assertTrue(result.regularFile)
        } finally {
            SafeTreeOps.deleteNoFollow(base)
        }
    }

    @Test
    fun rejectsSymlinkThatWalksAboveGuestRoot() {
        assertNull(GuestPath.resolveSymlinkTarget("bin/tool", "../../../outside"))
    }

    @Test
    fun detectsHardlinkCycle() {
        val base = Files.createTempDirectory("pocketpc-resolver-cycle-").toFile()
        try {
            val root = base.resolve("rootfs").apply { mkdirs() }
            val metadata = listOf(
                RootfsMetadataEntry(
                    RootfsEntryType.HARDLINK, 0, 0, 0, 0, 0, "a", "b"
                ),
                RootfsMetadataEntry(
                    RootfsEntryType.HARDLINK, 0, 0, 0, 0, 0, "b", "a"
                ),
            )

            val result = RootfsGuestResolver.resolve(root, metadata, "/a")

            assertFalse(result.regularFile)
            assertTrue(result.error?.contains("hardlink") == true)
        } finally {
            SafeTreeOps.deleteNoFollow(base)
        }
    }
}
