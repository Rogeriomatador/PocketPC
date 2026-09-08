package dev.pocketpc.core.runtime

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeProbeEvidenceFingerprintTest {
    @Test
    fun guestToolFingerprintChangesWhenArtifactIdentityChanges() {
        val base =
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
                            bytes = 123,
                            executable = true,
                        ),
                    ),
                executionMode = "native-aarch64",
            )

        val changed =
            base.copy(
                files =
                    listOf(
                        base.files.single().copy(
                            sha256 = "b".repeat(64),
                        ),
                    ),
            )

        assertNotEquals(
            GuestToolFingerprint.of(base),
            GuestToolFingerprint.of(changed),
        )
    }

    @Test
    fun wineIdentityUsesBox64ExecutionMode() {
        val wine =
            GuestToolManifest(
                schemaVersion = 1,
                id = "wine",
                version = "11.0",
                architecture = "x86_64",
                guestRoot = "/opt/pocketpc/wine",
                entrypoint = "bin/wine",
                sourceCommit =
                    "db11d0fe6a169c457e23d007e20404643d067aa8",
                license = "LGPL-2.1-or-later",
                files =
                    listOf(
                        GuestToolFile(
                            path = "bin/wine",
                            sha256 = "c".repeat(64),
                            bytes = 1,
                            executable = true,
                        ),
                    ),
                executionMode = "box64-x86_64",
            )

        assertTrue(
            GuestToolManifestValidator
                .errors(wine)
                .isEmpty(),
        )
        assertTrue(
            GuestToolTrustPolicy
                .errors(wine)
                .isEmpty(),
        )
    }
}
