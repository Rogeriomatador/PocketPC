package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

class GuestToolOverlayPlannerTest {
    @Test
    fun attestedBox64PackageProducesCanonicalSystemBind() {
        val root = Files.createTempDirectory("pocketpc-tool-overlay-").toFile()
        try {
            val toolDir = root.resolve("box64/0.4.4").apply { mkdirs() }
            val executable =
                toolDir.resolve("bin").apply { mkdirs() }
                    .resolve("box64").apply {
                        writeText("box64")
                        setExecutable(true)
                    }
            assumeTrue(executable.canExecute())
            val digest =
                executable.inputStream().use {
                    Sha256.digest(it)
                }
            val manifest =
                GuestToolManifest(
                    schemaVersion = 1,
                    id = "box64",
                    version = "0.4.4",
                    architecture = "aarch64",
                    guestRoot = "/opt/pocketpc/box64",
                    entrypoint = "bin/box64",
                    sourceCommit =
                        "2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a",
                    license = "MIT",
                    files =
                        listOf(
                            GuestToolFile(
                                path = "bin/box64",
                                sha256 = digest.sha256,
                                bytes = digest.bytes,
                                executable = true,
                            ),
                        ),
                )
            toolDir.resolve("guest-tool-manifest.json").writeText("{}")
            val installed =
                InstalledGuestTool(
                    manifest = manifest,
                    directory = toolDir,
                    entrypoint = executable,
                )

            val plan =
                GuestToolOverlayPlanner.plan(
                    tools = listOf(installed),
                    allowedHostRoots = listOf(root),
                )

            assertTrue(plan.blockers.joinToString(), plan.valid)
            assertTrue(plan.binds.size == 1)
            assertTrue(
                plan.binds.single().guestPath ==
                    "/opt/pocketpc/box64",
            )
            assertTrue(
                plan.binds.single().authority ==
                    BindAuthority.SYSTEM,
            )
        } finally {
            SafeTreeOps.deleteNoFollow(root)
        }
    }

    @Test
    fun tamperedInstalledToolDoesNotProduceBind() {
        val root = Files.createTempDirectory("pocketpc-tool-overlay-bad-").toFile()
        try {
            val toolDir = root.resolve("box64/0.4.4").apply { mkdirs() }
            val executable =
                toolDir.resolve("bin").apply { mkdirs() }
                    .resolve("box64").apply {
                        writeText("tampered")
                        setExecutable(true)
                    }
            assumeTrue(executable.canExecute())
            toolDir.resolve("guest-tool-manifest.json").writeText("{}")
            val manifest =
                GuestToolManifest(
                    schemaVersion = 1,
                    id = "box64",
                    version = "0.4.4",
                    architecture = "aarch64",
                    guestRoot = "/opt/pocketpc/box64",
                    entrypoint = "bin/box64",
                    sourceCommit =
                        "2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a",
                    license = "MIT",
                    files =
                        listOf(
                            GuestToolFile(
                                path = "bin/box64",
                                sha256 = "a".repeat(64),
                                bytes = executable.length(),
                                executable = true,
                            ),
                        ),
                )

            val plan =
                GuestToolOverlayPlanner.plan(
                    tools =
                        listOf(
                            InstalledGuestTool(
                                manifest,
                                toolDir,
                                executable,
                            ),
                        ),
                    allowedHostRoots = listOf(root),
                )

            assertFalse(plan.valid)
            assertTrue(plan.binds.isEmpty())
            assertTrue(
                plan.blockers.any {
                    it.contains("GUEST_TOOL_ATTESTATION_FAILED")
                },
            )
        } finally {
            SafeTreeOps.deleteNoFollow(root)
        }
    }
}
