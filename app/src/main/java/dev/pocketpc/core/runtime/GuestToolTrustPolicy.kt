package dev.pocketpc.core.runtime

object GuestToolTrustPolicy {
    data class TrustedTool(
        val id: String,
        val version: String,
        val sourceCommit: String,
        val license: String,
        val entrypoint: String,
    )

    private val trusted =
        mapOf(
            "box64" to
                TrustedTool(
                    id = "box64",
                    version = "0.4.4",
                    sourceCommit =
                        "2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a",
                    license = "MIT",
                    entrypoint = "bin/box64",
                ),
            "wine" to
                TrustedTool(
                    id = "wine",
                    version = "11.0",
                    sourceCommit =
                        "db11d0fe6a169c457e23d007e20404643d067aa8",
                    license = "LGPL-2.1-or-later",
                    entrypoint = "bin/wine",
                ),
        )

    fun errors(
        manifest: GuestToolManifest,
    ): List<String> {
        val expected =
            trusted[manifest.id]
                ?: return listOf(
                    "GUEST_TOOL_NOT_TRUSTED:" +
                        manifest.id,
                )

        val errors = mutableListOf<String>()
        if (manifest.version != expected.version) {
            errors +=
                "GUEST_TOOL_VERSION_NOT_TRUSTED:" +
                    manifest.id
        }
        if (
            manifest.sourceCommit !=
            expected.sourceCommit
        ) {
            errors +=
                "GUEST_TOOL_SOURCE_NOT_TRUSTED:" +
                    manifest.id
        }
        if (manifest.license != expected.license) {
            errors +=
                "GUEST_TOOL_LICENSE_NOT_TRUSTED:" +
                    manifest.id
        }
        if (
            manifest.entrypoint !=
            expected.entrypoint
        ) {
            errors +=
                "GUEST_TOOL_ENTRYPOINT_NOT_TRUSTED:" +
                    manifest.id
        }
        return errors
    }
}
