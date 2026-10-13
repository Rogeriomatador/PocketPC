package dev.pocketpc.core.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotDeviceValidationCandidateTest {
    @Test
    fun attestedCandidateAllowsOnlyShellAndRootfsAndStillNeedsApproval() {
        val fixture = fixture()
        try {
            val shell =
                ProotInvocationPlanner.buildProbe(
                    runtime = fixture.runtime,
                    substrate = fixture.substrate,
                    binds = fixture.binds,
                    allowedHostRoots = listOf(fixture.base),
                    probe = GuestRuntimeProbe.SHELL,
                )
            val rootfs =
                ProotInvocationPlanner.buildProbe(
                    runtime = fixture.runtime,
                    substrate = fixture.substrate,
                    binds = fixture.binds,
                    allowedHostRoots = listOf(fixture.base),
                    probe = GuestRuntimeProbe.ROOTFS,
                )

            listOf(shell, rootfs).forEach { plan ->
                assertTrue(plan.argv.isNotEmpty())
                assertEquals(
                    listOf(
                        ProotExecutionController.EXECUTION_APPROVAL_BLOCKER,
                    ),
                    plan.blockers,
                )
            }

            val box64 =
                ProotInvocationPlanner.buildProbe(
                    runtime = fixture.runtime,
                    substrate = fixture.substrate,
                    binds = fixture.binds,
                    allowedHostRoots = listOf(fixture.base),
                    probe = GuestRuntimeProbe.BOX64_SMOKE,
                )
            assertTrue(box64.argv.isEmpty())
            assertTrue("SUBSTRATE_NOT_READY" in box64.blockers)

            val explicitlyDisabled =
                ProotInvocationPlanner.buildProbe(
                    runtime = fixture.runtime,
                    substrate = fixture.substrate,
                    binds = fixture.binds,
                    allowedHostRoots = listOf(fixture.base),
                    probe = GuestRuntimeProbe.SHELL,
                    allowDeviceValidationCandidate = false,
                )
            assertTrue(explicitlyDisabled.argv.isEmpty())
            assertTrue(
                "SUBSTRATE_NOT_READY" in
                    explicitlyDisabled.blockers,
            )
        } finally {
            fixture.base.deleteRecursively()
        }
    }

    private data class Fixture(
        val base: File,
        val runtime: InstalledRuntime,
        val substrate: ExecutionSubstrateStatus,
        val binds: List<RuntimeBindSpec>,
    )

    private fun fixture(): Fixture {
        val base =
            Files.createTempDirectory(
                "pocketpc-device-validation-",
            ).toFile()
        val nativeDir =
            base.resolve("native")
                .apply { mkdirs() }
        nativeDir.resolve("libproot.so")
            .apply {
                writeText("placeholder")
                setExecutable(true)
            }
        nativeDir.resolve("libproot_loader.so")
            .apply {
                writeText("placeholder")
                setExecutable(true)
            }

        val rootfs =
            base.resolve("rootfs")
                .apply { mkdirs() }
        rootfs.resolve("bin").mkdirs()
        rootfs.resolve("bin/sh")
            .writeText("guest-data")
        val metadata =
            base.resolve("metadata")
        RootfsMetadataTestUtils.write(
            metadata,
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
                ),
            ),
        )
        val home =
            base.resolve("home")
                .apply { mkdirs() }

        val runtime =
            InstalledRuntime(
                manifest =
                    RuntimeManifest(
                        schemaVersion = 2,
                        id = "candidate.runtime",
                        name = "Candidate Runtime",
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
                stats =
                    ExtractionStats(
                        1,
                        1,
                        0,
                        0,
                        10,
                    ),
            )
        val substrate =
            ExecutionSubstrateStatus(
                nativeLibraryDir = nativeDir.path,
                packagedHostReady = true,
                prootReady = false,
                components = emptyList(),
                state =
                    "DEVICE_VALIDATION_CANDIDATE_ATTESTED_NOT_PRODUCTION_APPROVED",
                artifactContractApproved = false,
                policyDigestsVerified = false,
                artifactIntegrityVerified = false,
                deviceValidationReady = true,
                deviceValidationState =
                    "DEVICE_VALIDATION_CANDIDATE_ATTESTED",
            )
        val binds =
            listOf(
                RuntimeBindSpec(
                    hostPath = home,
                    guestPath = "/home/pocket",
                    readOnly = false,
                    purpose = "home",
                    authority = BindAuthority.SYSTEM,
                ),
                RuntimeBindSpec(
                    hostPath = home,
                    guestPath = "/tmp",
                    readOnly = false,
                    purpose = "temp",
                    authority = BindAuthority.SYSTEM,
                ),
            )

        return Fixture(
            base = base,
            runtime = runtime,
            substrate = substrate,
            binds = binds,
        )
    }
}
