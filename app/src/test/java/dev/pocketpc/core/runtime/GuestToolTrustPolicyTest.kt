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
