package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RuntimeLaunchPlannerTest {
    @Test
    fun alpha5NeverClaimsReadyWithoutExecutor() {
        val base = Files.createTempDirectory("pocketpc-launch-").toFile()
        try {
            val rootfs = base.resolve("rootfs-data").apply { mkdirs() }
            rootfs.resolve("bin").mkdirs()
            rootfs.resolve("bin/sh").writeText("guest-data")

            val runtime = InstalledRuntime(
                manifest = RuntimeManifest(
                    schemaVersion = 2,
                    id = "test.runtime",
                    name = "Test",
                    version = "1",
                    architecture = "aarch64",
                    rootfsSha256 = "a".repeat(64),
                    rootfsBytes = 1024,
                    entrypoint = "/bin/sh",
                    license = "test",
                    archiveFormat = "tar",
                    extractedBytesLimit = 4096,
                    entryLimit = 10,
                ),
                directory = base,
                rootfsData = rootfs,
                metadataFile = base.resolve("metadata"),
                stats = ExtractionStats(1, 1, 0, 0, 10),
            )
            val substrate = ExecutionSubstrateStatus(
                nativeLibraryDir = "/fake",
                packagedHostReady = true,
                prootReady = true,
                components = emptyList(),
                state = "PROOT_COMPONENTS_PRESENT_UNVALIDATED",
            )

            val result = RuntimeLaunchPlanner.assess(runtime, substrate)

            assertFalse(result.ready)
            assertTrue(LaunchBlocker.EXECUTOR_DISABLED in result.blockers)
        } finally {
            base.deleteRecursively()
        }
    }
}
