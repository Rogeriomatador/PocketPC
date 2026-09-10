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
                        GuestToolFile(
                            path = "lib/wine/x86_64-windows/winepocketpc.drv",
                            sha256 = "d".repeat(64),
                            bytes = 1,
                            executable = false,
                        ),
                        GuestToolFile(
                            path = "lib/wine/x86_64-unix/winepocketpc.so",
                            sha256 = "e".repeat(64),
                            bytes = 1,
                            executable = false,
                        ),
                        GuestToolFile(
                            path = "share/tests/pocketpc-win64-smoke.exe",
                            sha256 = "f".repeat(64),
                            bytes = 1,
                            executable = false,
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

    @Test
    fun graphicsLayerFingerprintChangesWhenDllHashChanges() {
        val base =
            WindowsRuntimeLayerManifest(
                schemaVersion = 1,
                id = "dxvk",
                version = "3.0.2",
                sourceCommit =
                    "6b20f622a77b87b2921fe5d2c1774d2f2ba3e9b7",
                license = "Zlib",
                windowsArchitecture =
                    "x86_64-windows",
                targetDirectory =
                    "drive_c/windows/system32",
                files =
                    listOf(
                        WindowsRuntimeLayerFile(
                            path = "dll/d3d11.dll",
                            destinationName =
                                "d3d11.dll",
                            sha256 =
                                "a".repeat(64),
                            bytes = 100,
                        ),
                        WindowsRuntimeLayerFile(
                            path = "dll/dxgi.dll",
                            destinationName =
                                "dxgi.dll",
                            sha256 =
                                "b".repeat(64),
                            bytes = 200,
                        ),
                    ),
            )

        val changed =
            base.copy(
                files =
                    base.files.map {
                        if (
                            it.destinationName ==
                            "d3d11.dll"
                        ) {
                            it.copy(
                                sha256 =
                                    "c".repeat(64),
                            )
                        } else {
                            it
                        }
                    },
            )

        assertNotEquals(
            WindowsRuntimeLayerFingerprint
                .of(base),
            WindowsRuntimeLayerFingerprint
                .of(changed),
        )
    }
}
