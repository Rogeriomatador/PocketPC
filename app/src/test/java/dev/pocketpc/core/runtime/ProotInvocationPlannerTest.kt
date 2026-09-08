package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProotInvocationPlannerTest {
    @Test
    fun buildsArgumentVectorButKeepsExecutorDisabled() {
        val base = Files.createTempDirectory("pocketpc-proot-plan-").toFile()
        try {
            val nativeDir = base.resolve("native").apply { mkdirs() }
            nativeDir.resolve("libproot.so").apply {
                writeText("placeholder")
                setExecutable(true)
            }
            nativeDir.resolve("libproot_loader.so").apply {
                writeText("placeholder")
                setExecutable(true)
            }

            val rootfs = base.resolve("rootfs").apply { mkdirs() }
            rootfs.resolve("bin").mkdirs()
            rootfs.resolve("bin/sh").writeText("guest-data")
            val metadata = base.resolve("metadata")
            writeEntrypointMetadata(metadata)
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
                metadataFile = metadata,
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
                ),
                RuntimeBindSpec(home, "/tmp", false, "temp", BindAuthority.SYSTEM),
            )

            val plan = ProotInvocationPlanner.build(
                runtime = runtime,
                substrate = substrate,
                binds = binds,
                allowedHostRoots = listOf(base),
            )

            assertEquals(home.canonicalPath, plan.environment["PROOT_TMP_DIR"])
            assertEquals("/tmp", plan.environment["TMPDIR"])
            val missingTemp = ProotInvocationPlanner.build(runtime, substrate, binds.take(1), listOf(base))
            assertTrue("HOST_TEMP_BIND_MISSING" in missingTemp.blockers)
            assertTrue(missingTemp.argv.isEmpty())

            val probe = ProotInvocationPlanner.buildProbe(runtime, substrate, binds, listOf(base), GuestRuntimeProbe.SHELL)
            assertEquals(listOf("/bin/sh") + GuestRuntimeProbe.SHELL.arguments, probe.argv.takeLast(3))
            assertEquals(listOf("EXECUTION_REQUIRES_USER_APPROVAL"), probe.blockers)
            val invalid = ProotInvocationPlanner.build(runtime, substrate, binds, listOf(base), listOf("bad\u0000arg"))
            assertTrue(invalid.argv.isEmpty())
            assertTrue("INVALID_GUEST_ARGUMENTS" in invalid.blockers)

            assertFalse(plan.ready)
            assertEquals(listOf("EXECUTION_REQUIRES_USER_APPROVAL"), plan.blockers)
            assertTrue(plan.argv.contains("-r"))
            assertTrue(plan.argv.contains("-b"))
            assertTrue(plan.argv.last() == "/bin/sh")
            assertTrue(
                plan.environment["PROOT_LOADER"] ==
                    nativeDir.resolve("libproot_loader.so").path
            )
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun blocksReadOnlyBindUntilSemanticsAreImplemented() {
        val base = Files.createTempDirectory("pocketpc-proot-ro-").toFile()
        try {
            val nativeDir = base.resolve("native").apply { mkdirs() }
            nativeDir.resolve("libproot.so").apply {
                writeText("placeholder")
                setExecutable(true)
            }
            nativeDir.resolve("libproot_loader.so").apply {
                writeText("placeholder")
                setExecutable(true)
            }

            val rootfs = base.resolve("rootfs").apply { mkdirs() }
            rootfs.resolve("bin").mkdirs()
            rootfs.resolve("bin/sh").writeText("guest-data")
            val metadata = base.resolve("metadata")
            writeEntrypointMetadata(metadata)
            val readonly = base.resolve("readonly").apply { mkdirs() }

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
                metadataFile = metadata,
                stats = ExtractionStats(1, 1, 0, 0, 10),
            )
            val substrate = ExecutionSubstrateStatus(
                nativeLibraryDir = nativeDir.path,
                packagedHostReady = true,
                prootReady = true,
                components = emptyList(),
                state = "PROOT_COMPONENTS_PRESENT_UNVALIDATED",
            )

            val plan = ProotInvocationPlanner.build(
                runtime = runtime,
                substrate = substrate,
                binds = listOf(
                    RuntimeBindSpec(
                        hostPath = readonly,
                        guestPath = "/mnt/readonly",
                        readOnly = true,
                        purpose = "test",
                        authority = BindAuthority.SYSTEM,
                    )
                ),
                allowedHostRoots = listOf(base),
            )

            assertFalse(plan.ready)
            assertTrue(
                plan.blockers.any { it.startsWith("READ_ONLY_BIND_UNIMPLEMENTED:") }
            )
            assertTrue(plan.argv.isEmpty())
        } finally {
            base.deleteRecursively()
        }
    }

    private fun writeEntrypointMetadata(file: File) {
        RootfsMetadataTestUtils.write(
            file,
            listOf(
                RootfsMetadataEntry(
                    RootfsEntryType.FILE,
                    493,
                    0,
                    0,
                    10,
                    0,
                    "bin/sh",
                    "",
                )
            ),
        )
    }
}
