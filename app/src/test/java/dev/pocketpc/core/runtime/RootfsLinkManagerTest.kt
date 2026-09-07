package dev.pocketpc.core.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

class RootfsLinkManagerTest {
    @Test
    fun preparesAndVerifiesSymlinkAndHardlink() = runBlocking {
        val base = Files.createTempDirectory("pocketpc-links-").toFile()
        try {
            assumeSymlinkSupport(base)
            val runtime = makeRuntime(base)
            val manager = RootfsLinkManager()

            val prepared = manager.prepare(runtime)
            assertTrue(prepared.message, prepared.prepared)
            assertEquals(1, prepared.symlinks)
            assertEquals(1, prepared.hardlinks)

            val symlink = runtime.rootfsData.resolve("bin").toPath()
            assertTrue(Files.isSymbolicLink(symlink))
            assertEquals(
                Paths.get("usr/bin").normalize(),
                Files.readSymbolicLink(symlink).normalize(),
            )

            val hardlink = runtime.rootfsData.resolve("usr/bin/sh-copy").toPath()
            val target = runtime.rootfsData.resolve("usr/bin/sh").toPath()
            assertTrue(Files.isSameFile(hardlink, target))

            val verified = manager.verify(runtime.copy(linksPrepared = true))
            assertTrue(verified.message, verified.prepared)
        } finally {
            SafeTreeOps.deleteNoFollow(base)
        }
    }

    @Test
    fun recoversInterruptedLinkCreationBeforeRetry() = runBlocking {
        val base = Files.createTempDirectory("pocketpc-links-recover-").toFile()
        try {
            assumeSymlinkSupport(base)
            val runtime = makeRuntime(base)
            val root = runtime.rootfsData.toPath()
            Files.createSymbolicLink(root.resolve("bin"), java.nio.file.Paths.get("usr/bin"))
            runtime.directory.resolve(RootfsLinkManager.PREPARING_MARKER_NAME)
                .writeText("schema=1\n")

            val result = RootfsLinkManager().prepare(runtime)

            assertTrue(result.message, result.prepared)
            assertTrue(Files.isSymbolicLink(root.resolve("bin")))
            assertFalse(
                Files.exists(
                    runtime.directory.resolve(RootfsLinkManager.PREPARING_MARKER_NAME).toPath(),
                    LinkOption.NOFOLLOW_LINKS,
                )
            )
        } finally {
            SafeTreeOps.deleteNoFollow(base)
        }
    }

    @Test
    fun safeDeleteDoesNotFollowAbsoluteGuestSymlink() {
        val base = Files.createTempDirectory("pocketpc-delete-").toFile()
        val outside = Files.createTempDirectory("pocketpc-outside-").toFile()
        try {
            assumeSymlinkSupport(base)
            outside.resolve("keep.txt").writeText("keep")
            val root = base.resolve("rootfs").apply { mkdirs() }
            Files.createSymbolicLink(
                root.resolve("escape").toPath(),
                outside.toPath().toAbsolutePath(),
            )

            assertTrue(SafeTreeOps.deleteNoFollow(base))
            assertTrue(outside.resolve("keep.txt").isFile)
            assertEquals("keep", outside.resolve("keep.txt").readText())
        } finally {
            SafeTreeOps.deleteNoFollow(base)
            SafeTreeOps.deleteNoFollow(outside)
        }
    }

    private fun makeRuntime(base: java.io.File): InstalledRuntime {
        val directory = base.resolve("installed").apply { mkdirs() }
        val root = directory.resolve("rootfs-data").apply { mkdirs() }
        root.resolve("usr/bin").mkdirs()
        root.resolve("usr/bin/sh").writeText("guest")

        val entries = listOf(
            RootfsMetadataEntry(
                RootfsEntryType.DIRECTORY, 493, 0, 0, 0, 0, "usr", ""
            ),
            RootfsMetadataEntry(
                RootfsEntryType.DIRECTORY, 493, 0, 0, 0, 0, "usr/bin", ""
            ),
            RootfsMetadataEntry(
                RootfsEntryType.FILE, 493, 0, 0, 5, 0, "usr/bin/sh", ""
            ),
            RootfsMetadataEntry(
                RootfsEntryType.SYMLINK, 511, 0, 0, 0, 0, "bin", "usr/bin"
            ),
            RootfsMetadataEntry(
                RootfsEntryType.HARDLINK, 493, 0, 0, 0, 0,
                "usr/bin/sh-copy", "usr/bin/sh"
            ),
        )
        val metadata = directory.resolve("rootfs.metadata.tsv")
        RootfsMetadataTestUtils.write(metadata, entries)

        return InstalledRuntime(
            manifest = RuntimeManifest(
                schemaVersion = 2,
                id = "test.runtime",
                name = "Test Runtime",
                version = "1",
                architecture = "aarch64",
                rootfsSha256 = "a".repeat(64),
                rootfsBytes = 1,
                entrypoint = "/bin/sh",
                license = "test",
                archiveFormat = "tar",
                extractedBytesLimit = 1024,
                entryLimit = 100,
            ),
            directory = directory,
            rootfsData = root,
            metadataFile = metadata,
            stats = ExtractionStats(
                entries = entries.size,
                regularFiles = 1,
                directories = 2,
                linksRecorded = 2,
                extractedBytes = 5,
            ),
            linksPrepared = false,
        )
    }

    private fun assumeSymlinkSupport(base: java.io.File) {
        val target = base.resolve("symlink-probe-target").apply { mkdirs() }
        val link = base.resolve("symlink-probe-link").toPath()
        val supported = runCatching {
            Files.createSymbolicLink(link, target.toPath())
            Files.isSymbolicLink(link)
        }.getOrDefault(false)
        Files.deleteIfExists(link)
        target.delete()
        assumeTrue("Host filesystem does not permit symbolic links", supported)
    }
}
