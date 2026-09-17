package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

class GuestToolPackageVerifierTest {
    @Test
    fun validSingleBinaryPackageIsAttested() {
        val root = Files.createTempDirectory("pocketpc-guest-tool-").toFile()
        try {
            val bin = root.resolve("bin").apply { mkdirs() }
            val executable = bin.resolve("box64").apply {
                writeText("box64-test")
            }
            executable.setExecutable(true)
            assumeTrue(executable.canExecute())

            val digest =
                executable.inputStream().use {
                    Sha256.digest(it)
                }
            val manifest = validManifest(digest.sha256, digest.bytes)
            root.resolve("guest-tool-manifest.json").writeText("{}")

            val result =
                GuestToolPackageVerifier.verify(
                    packageRoot = root,
                    manifest = manifest,
                )

            assertTrue(result.errors.joinToString(), result.valid)
            assertTrue(result.files.single().valid)
        } finally {
            SafeTreeOps.deleteNoFollow(root)
        }
    }

    @Test
    fun hashMismatchFailsClosed() {
        val root = Files.createTempDirectory("pocketpc-guest-tool-hash-").toFile()
        try {
            val executable =
                root.resolve("bin").apply { mkdirs() }
                    .resolve("box64").apply {
                        writeText("tampered")
                        setExecutable(true)
                    }
            assumeTrue(executable.canExecute())
            root.resolve("guest-tool-manifest.json").writeText("{}")

            val result =
                GuestToolPackageVerifier.verify(
                    packageRoot = root,
                    manifest =
                        validManifest(
                            sha256 = "a".repeat(64),
                            bytes = executable.length(),
                        ),
                )

            assertFalse(result.valid)
            assertTrue(
                result.errors.any {
                    it.startsWith("GUEST_TOOL_FILE_SHA256_MISMATCH")
                },
            )
        } finally {
            SafeTreeOps.deleteNoFollow(root)
        }
    }

    @Test
    fun unexpectedFileFailsClosed() {
        val root = Files.createTempDirectory("pocketpc-guest-tool-extra-").toFile()
        try {
            val executable =
                root.resolve("bin").apply { mkdirs() }
                    .resolve("box64").apply {
                        writeText("box64-test")
                        setExecutable(true)
                    }
            assumeTrue(executable.canExecute())
            val digest =
                executable.inputStream().use {
                    Sha256.digest(it)
                }
            root.resolve("guest-tool-manifest.json").writeText("{}")
            root.resolve("surprise.bin").writeText("unexpected")

            val result =
                GuestToolPackageVerifier.verify(
                    packageRoot = root,
                    manifest = validManifest(digest.sha256, digest.bytes),
                )

            assertFalse(result.valid)
            assertTrue(
                result.errors.contains(
                    "GUEST_TOOL_UNEXPECTED_FILE:surprise.bin",
                ),
            )
        } finally {
            SafeTreeOps.deleteNoFollow(root)
        }
    }

    @Test
    fun traversalInManifestIsRejectedBeforeFilesystemAccess() {
        val manifest =
            validManifest("a".repeat(64), 1L).copy(
                entrypoint = "../box64",
                files =
                    listOf(
                        GuestToolFile(
                            path = "../box64",
                            sha256 = "a".repeat(64),
                            bytes = 1L,
                            executable = true,
                        ),
                    ),
            )

        val errors = GuestToolManifestValidator.errors(manifest)

        assertTrue(
            errors.contains("GUEST_TOOL_ENTRYPOINT_INVALID"),
        )
        assertTrue(
            errors.any {
                it.startsWith("GUEST_TOOL_FILE_PATH_INVALID")
            },
        )
    }

    private fun validManifest(
        sha256: String,
        bytes: Long,
    ) =
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
                        sha256 = sha256,
                        bytes = bytes,
                        executable = true,
                    ),
                ),
        )
}
