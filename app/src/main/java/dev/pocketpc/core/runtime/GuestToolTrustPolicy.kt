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
        val schemaVersion: Int = 1,
        val sources: List<GuestToolSource> = emptyList(),
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
            "vulkan-stack" to
                TrustedTool(
                    id = "vulkan-stack",
                    version =
                        "26.2.2+loader1.4.357",
                    sourceCommit = "",
                    license =
                        "MIT+Apache-2.0",
                    entrypoint =
                        "bin/vulkan-smoke",
                    architecture = "aarch64",
                    executionMode =
                        "native-aarch64",
                    schemaVersion = 2,
                    sources =
                        listOf(
                            GuestToolSource(
                                id = "mesa",
                                version = "26.2.2",
                                location =
                                    "https://archive.mesa3d.org/mesa-26.2.2.tar.xz",
                                revisionType =
                                    "archive-sha256",
                                revision =
                                    "eeb29ca7e56cfaa8e8a79538dcf834e3b18e501c31bef5145e959ea437cc4216",
                            ),
                            GuestToolSource(
                                id = "vulkan-loader",
                                version = "1.4.357",
                                location =
                                    "https://github.com/KhronosGroup/Vulkan-Loader.git",
                                revisionType =
                                    "git-commit",
                                revision =
                                    "5f157b62e333c63260d05d81bf66faa216ab0fb8",
                            ),
                            GuestToolSource(
                                id = "vulkan-headers",
                                version = "1.4.357",
                                location =
                                    "https://github.com/KhronosGroup/Vulkan-Headers.git",
                                revisionType =
                                    "git-commit",
                                revision =
                                    "e3b1eec08173d6b825cd3ac88c885a63b621504a",
                            ),
                        ),
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
        if (
            manifest.schemaVersion !=
            expected.schemaVersion
        ) {
            errors +=
                "GUEST_TOOL_SCHEMA_NOT_TRUSTED:" +
                    manifest.id
        }
        if (manifest.version != expected.version) {
            errors +=
                "GUEST_TOOL_VERSION_NOT_TRUSTED:" +
                    manifest.id
        }
        if (
            manifest.schemaVersion == 1 &&
            manifest.sourceCommit !=
            expected.sourceCommit
        ) {
            errors +=
                "GUEST_TOOL_SOURCE_NOT_TRUSTED:" +
                    manifest.id
        }
        if (
            manifest.schemaVersion == 2 &&
            manifest.sources !=
            expected.sources
        ) {
            errors +=
                "GUEST_TOOL_SOURCES_NOT_TRUSTED:" +
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

        val paths =
            manifest.files
                .map { it.path }
                .toSet()

        if (manifest.id == "wine") {
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

        if (manifest.id == "vulkan-stack") {
            val required =
                setOf(
                    "bin/vulkan-smoke",
                    "lib/libvulkan.so.1",
                    "lib/libvulkan_freedreno.so",
                    "share/vulkan/icd.d/freedreno_icd.aarch64.json",
                )
            required
                .filterNot(paths::contains)
                .forEach {
                    errors +=
                        "GUEST_TOOL_VULKAN_STACK_FILE_MISSING:" +
                            it
                }
        }

        return errors
    }
}
