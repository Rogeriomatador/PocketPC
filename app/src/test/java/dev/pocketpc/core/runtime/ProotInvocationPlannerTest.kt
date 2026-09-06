package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ProotInvocationPlannerTest {
    @Test
    fun buildsArgumentVectorButKeepsExecutorDisabled() {
        val base = Files.createTempDirectory("pocketpc-proot-plan-").toFile()
        try {
            val nativeDir = base.resolve("native").apply { mkdirs() }
            val proot = nativeDir.resolve("libproot.so").apply {
                writeText("placeholder")
                setExecutable(true)
            }
            assertTrue(proot.isFile)

            val rootfs = base.resolve("rootfs").apply { mkdirs() }
            rootfs.resolve("bin").mkdirs()
            rootfs.resolve("bin/sh").writeText("guest-data")
            val home = base.resolve("home").apply { mkdirs() }

            val runtime = InstalledRuntime(
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
                    entryLimit = 10,
                ),
                directory = base,
                rootfsData = rootfs,
                metadataFile = base.resolve("metadata"),
                stats = ExtractionStats(1, 1, 0, 0, 10),
            )
            val substrate = ExecutionSubstrateStatus(
                nativeLibraryDir = nativeDir.path,
                packagedHostReady = true,
                prootReady = true,
                components = emptyList(),
                state = "PROOT_COMPONENTS_PRESENT_UNVALIDATED",
            )
            val binds = listOf(
                RuntimeBindSpec(
                    hostPath = home,
                    guestPath = "/home/pocket",
                    readOnly = false,
                    purpose = "home",
                    authority = BindAuthority.SYSTEM,
                )
            )

            val plan = ProotInvocationPlanner.build(
                runtime = runtime,
                substrate = substrate,
                binds = binds,
                allowedHostRoots = listOf(base),
            )

            assertFalse(plan.ready)
            assertTrue("EXECUTOR_NOT_ENABLED" in plan.blockers)
            assertTrue(plan.argv.contains("-r"))
            assertTrue(plan.argv.contains("-b"))
            assertTrue(plan.argv.last() == "/bin/sh")
        } finally {
            base.deleteRecursively()
        }
    }
}
