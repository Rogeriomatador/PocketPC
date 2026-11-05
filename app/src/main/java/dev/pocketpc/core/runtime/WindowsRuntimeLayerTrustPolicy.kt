package dev.pocketpc.core.runtime

object WindowsRuntimeLayerTrustPolicy {
    data class TrustedLayer(
        val id: String,
        val version: String,
        val sourceCommit: String,
        val license: String,
        val allowedDlls: Set<String>,
        val requiredDlls: Set<String>,
    )

    private val trusted =
        mapOf(
            "dxvk" to
                TrustedLayer(
                    id = "dxvk",
                    version = "3.0.2",
                    sourceCommit =
                        "6b20f622a77b87b2921fe5d2c1774d2f2ba3e9b7",
                    license = "Zlib",
                    allowedDlls =
                        setOf(
                            "d3d8.dll",
                            "d3d9.dll",
                            "d3d10core.dll",
                            "d3d11.dll",
                            "dxgi.dll",
                        ),
                    requiredDlls =
                        setOf(
                            "d3d11.dll",
                            "dxgi.dll",
                        ),
                ),
            "vkd3d-proton" to
                TrustedLayer(
                    id = "vkd3d-proton",
                    version = "3.0.1",
                    sourceCommit =
                        "3b10bd7a7ec6a7347e616cf8bea59333afec2255",
                    license =
                        "LGPL-2.1-or-later",
                    allowedDlls =
                        setOf(
                            "d3d12.dll",
                            "d3d12core.dll",
                        ),
                    requiredDlls =
                        setOf(
                            "d3d12.dll",
                        ),
                ),
        )

    fun errors(
        manifest: WindowsRuntimeLayerManifest,
    ): List<String> {
        val expected =
            trusted[manifest.id]
                ?: return listOf(
                    "WINDOWS_LAYER_NOT_TRUSTED:" +
                        manifest.id,
                )

        val errors = mutableListOf<String>()

        if (manifest.version != expected.version) {
            errors +=
                "WINDOWS_LAYER_VERSION_NOT_TRUSTED:" +
                    manifest.id
        }
        if (
            manifest.sourceCommit !=
            expected.sourceCommit
        ) {
            errors +=
                "WINDOWS_LAYER_SOURCE_NOT_TRUSTED:" +
                    manifest.id
        }
        if (manifest.license != expected.license) {
            errors +=
                "WINDOWS_LAYER_LICENSE_NOT_TRUSTED:" +
                    manifest.id
        }

        val destinations =
            manifest.files
                .map {
                    it.destinationName
                        .lowercase()
                }
                .toSet()

        destinations
            .filterNot(
                expected.allowedDlls::contains,
            )
            .forEach { dll ->
                errors +=
                    "WINDOWS_LAYER_DLL_NOT_ALLOWED:" +
                        dll
            }

        expected.requiredDlls
            .filterNot(destinations::contains)
            .forEach { dll ->
                errors +=
                    "WINDOWS_LAYER_REQUIRED_DLL_MISSING:" +
                        dll
            }

        return errors.distinct()
    }
}
