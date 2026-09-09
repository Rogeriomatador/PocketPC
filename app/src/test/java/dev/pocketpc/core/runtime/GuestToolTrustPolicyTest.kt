package dev.pocketpc.core.runtime

import org.junit.Assert.assertTrue
import org.junit.Test

class GuestToolTrustPolicyTest {
    @Test
    fun acceptsPinnedBox64Identity() {
        val errors =
            GuestToolTrustPolicy.errors(
                box64Manifest(),
            )
        assertTrue(errors.joinToString(), errors.isEmpty())
    }

    @Test
    fun rejectsBox64FromDifferentCommit() {
        val errors =
            GuestToolTrustPolicy.errors(
                box64Manifest().copy(
                    sourceCommit =
                        "0".repeat(40),
                ),
            )
        assertTrue(
            errors.contains(
                "GUEST_TOOL_SOURCE_NOT_TRUSTED:box64",
            ),
        )
    }

    @Test
    fun rejectsUnknownToolId() {
        val errors =
            GuestToolTrustPolicy.errors(
                box64Manifest().copy(
                    id = "unknown",
                    guestRoot =
                        "/opt/pocketpc/unknown",
                ),
            )
        assertTrue(
            errors.contains(
                "GUEST_TOOL_NOT_TRUSTED:unknown",
            ),
        )
    }

    @Test
    fun acceptsPinnedWineWithPocketPcDriver() {
        val errors =
            GuestToolTrustPolicy.errors(
                wineManifest(),
            )
        assertTrue(
            errors.joinToString(),
            errors.isEmpty(),
        )
    }

    @Test
    fun rejectsWineWithoutPocketPcDriverPe() {
        val errors =
            GuestToolTrustPolicy.errors(
                wineManifest().copy(
                    files =
                        wineManifest().files
                            .filterNot {
                                it.path.endsWith(
                                    "/winepocketpc.drv",
                                )
                            },
                ),
            )
        assertTrue(
            errors.contains(
                "GUEST_TOOL_WINE_DRIVER_PE_MISSING",
            ),
        )
    }

    @Test
    fun rejectsWineWithoutPocketPcDriverUnixLibrary() {
        val errors =
            GuestToolTrustPolicy.errors(
                wineManifest().copy(
                    files =
                        wineManifest().files
                            .filterNot {
                                it.path.endsWith(
                                    "/winepocketpc.so",
                                )
                            },
                ),
            )
        assertTrue(
            errors.contains(
                "GUEST_TOOL_WINE_DRIVER_UNIXLIB_MISSING",
            ),
        )
    }

    private fun wineManifest() =
        GuestToolManifest(
            schemaVersion = 1,
            id = "wine",
            version = "11.0",
            architecture = "x86_64",
            executionMode =
                "box64-x86_64",
            guestRoot =
                "/opt/pocketpc/wine",
            entrypoint = "bin/wine",
            sourceCommit =
                "db11d0fe6a169c457e23d007e20404643d067aa8",
            license =
                "LGPL-2.1-or-later",
            files =
                listOf(
                    GuestToolFile(
                        path =
                            "bin/wine",
                        sha256 =
                            "a".repeat(64),
                        bytes = 1,
                        executable = true,
                    ),
                    GuestToolFile(
                        path =
                            "lib/wine/x86_64-windows/winepocketpc.drv",
                        sha256 =
                            "b".repeat(64),
                        bytes = 2,
                        executable = false,
                    ),
                    GuestToolFile(
                        path =
                            "lib/wine/x86_64-unix/winepocketpc.so",
                        sha256 =
                            "c".repeat(64),
                        bytes = 3,
                        executable = true,
                    ),
                    GuestToolFile(
                        path =
                            "share/tests/pocketpc-win64-smoke.exe",
                        sha256 =
                            "d".repeat(64),
                        bytes = 4,
                        executable = false,
                    ),
                ),
        )

    private fun box64Manifest() =
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
                        bytes = 1,
                        executable = true,
                    ),
                ),
        )
}
