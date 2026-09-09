package dev.pocketpc.core.runtime

object GuestToolTrustPolicy {
    data class TrustedTool(
        val id: String,
        val version: String,
        val sourceCommit: String,
        val license: String,
        val entrypoint: String,
        val architecture: String,
        val executionMode: String,
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
                    architecture = "aarch64",
                    executionMode = "native-aarch64",
                ),
            "wine" to
                TrustedTool(
                    id = "wine",
                    version = "11.0",
                    sourceCommit =
                        "db11d0fe6a169c457e23d007e20404643d067aa8",
                    license = "LGPL-2.1-or-later",
                    entrypoint = "bin/wine",
                    architecture = "x86_64",
                    executionMode = "box64-x86_64",
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
        if (manifest.architecture != expected.architecture) {
            errors +=
                "GUEST_TOOL_ARCHITECTURE_NOT_TRUSTED:" +
                    manifest.id
        }
        if (manifest.executionMode != expected.executionMode) {
            errors +=
                "GUEST_TOOL_EXECUTION_MODE_NOT_TRUSTED:" +
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

        if (manifest.id == "wine") {
            val paths =
                manifest.files
                    .map { it.path }
                    .toSet()
            if (
                paths.none {
                    it.endsWith(
                        "/winepocketpc.drv",
                    ) ||
                        it ==
                        "winepocketpc.drv"
                }
            ) {
                errors +=
                    "GUEST_TOOL_WINE_DRIVER_PE_MISSING"
            }
            if (
                paths.none {
                    it.endsWith(
                        "/winepocketpc.so",
                    ) ||
                        it ==
                        "winepocketpc.so"
                }
            ) {
                errors +=
                    "GUEST_TOOL_WINE_DRIVER_UNIXLIB_MISSING"
            }
            if (
                "share/tests/pocketpc-win64-smoke.exe"
                !in paths
            ) {
                errors +=
                    "GUEST_TOOL_WINE_SMOKE_FIXTURE_MISSING"
            }
        }

        return errors
    }
}
