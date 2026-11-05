package dev.pocketpc.core.runtime

import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

class RuntimeExecutionIdentityTest {
    @Test
    fun rootfsDigestChangeInvalidatesExecutionIdentity() {
        val runtimeA =
            runtime(
                rootfsSha =
                    "a".repeat(64),
            )
        val runtimeB =
            runtime(
                rootfsSha =
                    "b".repeat(64),
            )
        val tools =
            listOf(box64("c".repeat(64)))

        assertNotEquals(
            RuntimeExecutionIdentity.of(
                runtimeA,
                tools,
            ),
            RuntimeExecutionIdentity.of(
                runtimeB,
                tools,
            ),
        )
    }

    @Test
    fun toolArtifactChangeInvalidatesExecutionIdentity() {
        val runtime =
            runtime(
                rootfsSha =
                    "a".repeat(64),
            )

        assertNotEquals(
            RuntimeExecutionIdentity.of(
                runtime,
                listOf(
                    box64(
                        "c".repeat(64),
                    ),
                ),
            ),
            RuntimeExecutionIdentity.of(
                runtime,
                listOf(
                    box64(
                        "d".repeat(64),
                    ),
                ),
            ),
        )
    }

    private fun runtime(
        rootfsSha: String,
    ): InstalledRuntime =
        InstalledRuntime(
            manifest =
                RuntimeManifest(
                    schemaVersion = 2,
                    id = "ubuntu-base",
                    name = "Ubuntu Base",
                    version = "24.04.4",
                    architecture = "aarch64",
                    rootfsSha256 =
                        rootfsSha,
                    rootfsBytes = 1,
                    entrypoint = "/bin/sh",
                    license = "mixed",
                    archiveFormat =
                        "tar.gz",
                    extractedBytesLimit =
                        1024,
                    entryLimit = 10,
                ),
            directory =
                File("/runtime"),
            rootfsData =
                File("/runtime/rootfs-data"),
            metadataFile =
                File(
                    "/runtime/rootfs.metadata.tsv",
                ),
            stats =
                ExtractionStats(
                    entries = 1,
                    regularFiles = 1,
                    directories = 0,
                    linksRecorded = 0,
                    extractedBytes = 1,
                ),
        )

    private fun box64(
        artifactSha: String,
    ): InstalledGuestTool {
        val manifest =
            GuestToolManifest(
                schemaVersion = 1,
                id = "box64",
                version = "0.4.4",
                architecture = "aarch64",
                guestRoot =
                    "/opt/pocketpc/box64",
                entrypoint = "bin/box64",
                sourceCommit =
                    "2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a",
                license = "MIT",
                files =
                    listOf(
                        GuestToolFile(
                            path = "bin/box64",
                            sha256 =
                                artifactSha,
                            bytes = 123,
                            executable = true,
                        ),
                    ),
                executionMode =
                    "native-aarch64",
            )
        val directory =
            File("/tools/box64/0.4.4")
        return InstalledGuestTool(
            manifest = manifest,
            directory = directory,
            entrypoint =
                File(
                    directory,
                    "bin/box64",
                ),
        )
    }
}
