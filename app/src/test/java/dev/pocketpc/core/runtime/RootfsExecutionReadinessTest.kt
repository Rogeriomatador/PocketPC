package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RootfsExecutionReadinessTest {
    @Test
    fun readyRootfsRequiresResolvedEntrypoint() {
        val base = Files.createTempDirectory("pocketpc-rootfs-ready-").toFile()
        try {
            val root = base.resolve("rootfs-data").apply { mkdirs() }
            root.resolve("bin").mkdirs()
            root.resolve("bin/sh").apply {
                writeText("guest")
                setExecutable(true)
            }
            val metadata = base.resolve("rootfs.metadata.tsv")
            RootfsMetadataTestUtils.write(
                metadata,
                listOf(
                    RootfsMetadataEntry(
                        RootfsEntryType.DIRECTORY, 493, 0, 0, 0, 0, "bin", ""
                    ),
                    RootfsMetadataEntry(
                        RootfsEntryType.FILE, 493, 0, 0, 5, 0, "bin/sh", ""
                    ),
                ),
            )
            val runtime = InstalledRuntime(
                manifest = RuntimeManifest(
                    schemaVersion = 2,
                    id = "linux",
                    name = "Linux",
                    version = "1",
                    architecture = "aarch64",
                    rootfsSha256 = "a".repeat(64),
                    rootfsBytes = 1,
                    entrypoint = "/bin/sh",
                    license = "test",
                    archiveFormat = "tar",
                    extractedBytesLimit = 1024,
                    entryLimit = 16,
                ),
                directory = base,
                rootfsData = root,
                metadataFile = metadata,
                stats = ExtractionStats(2, 1, 1, 0, 5),
                linksPrepared = false,
            )

            val result = RootfsExecutionReadinessProbe.assess(runtime)

            assertTrue(result.ready)
            assertTrue(result.entrypointResolved)
            assertTrue(result.linksPrepared)
        } finally {
            SafeTreeOps.deleteNoFollow(base)
        }
    }

    @Test
    fun missingEntrypointFailsClosed() {
        val base = Files.createTempDirectory("pocketpc-rootfs-missing-").toFile()
        try {
            val root = base.resolve("rootfs-data").apply { mkdirs() }
            val metadata = base.resolve("rootfs.metadata.tsv")
            RootfsMetadataTestUtils.write(metadata, emptyList())
            val runtime = InstalledRuntime(
                manifest = RuntimeManifest(
                    schemaVersion = 2,
                    id = "linux",
                    name = "Linux",
                    version = "1",
                    architecture = "aarch64",
                    rootfsSha256 = "a".repeat(64),
                    rootfsBytes = 1,
                    entrypoint = "/bin/sh",
                    license = "test",
                    archiveFormat = "tar",
                    extractedBytesLimit = 1024,
                    entryLimit = 16,
                ),
                directory = base,
                rootfsData = root,
                metadataFile = metadata,
                stats = ExtractionStats(0, 0, 0, 0, 0),
                linksPrepared = false,
            )

            val result = RootfsExecutionReadinessProbe.assess(runtime)

            assertFalse(result.ready)
            assertFalse(result.entrypointResolved)
            assertTrue(result.errors.any { it.startsWith("ROOTFS_ENTRYPOINT_UNAVAILABLE") })
        } finally {
            SafeTreeOps.deleteNoFollow(base)
        }
    }
}
