package dev.pocketpc.core.runtime

import android.content.Context
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

data class RuntimeProbeEvidenceState(
    val box64SmokePassed: Boolean,
    val wineSmokePassed: Boolean,
    val d3d11SmokePassed: Boolean = false,
)

object GuestToolFingerprint {
    fun of(
        manifest: GuestToolManifest,
    ): String =
        digestCanonical(
            buildString {
                appendLine(manifest.id)
                appendLine(manifest.version)
                appendLine(manifest.architecture)
                appendLine(manifest.executionMode)
                appendLine(manifest.sourceCommit)
                appendLine(manifest.license)
                appendLine(manifest.entrypoint)
                manifest.files
                    .sortedBy { it.path }
                    .forEach { item ->
                        append(item.path)
                        append('|')
                        append(item.bytes)
                        append('|')
                        append(item.sha256)
                        append('|')
                        append(item.executable)
                        appendLine()
                    }
            },
        )
}

object WindowsRuntimeLayerFingerprint {
    fun of(
        manifest: WindowsRuntimeLayerManifest,
    ): String =
        digestCanonical(
            buildString {
                appendLine(manifest.id)
                appendLine(manifest.version)
                appendLine(manifest.sourceCommit)
                appendLine(manifest.license)
                appendLine(
                    manifest.windowsArchitecture,
                )
                appendLine(
                    manifest.targetDirectory,
                )
                manifest.files
                    .sortedBy {
                        it.destinationName
                    }
                    .forEach { item ->
                        append(item.path)
                        append('|')
                        append(item.destinationName)
                        append('|')
                        append(item.bytes)
                        append('|')
                        append(item.sha256)
                        appendLine()
                    }
            },
        )
}

private fun digestCanonical(
    canonical: String,
): String =
    Sha256.digest(
        ByteArrayInputStream(
            canonical.toByteArray(
                StandardCharsets.UTF_8,
            ),
        ),
    ).sha256

class RuntimeProbeEvidenceStore(
    context: Context,
) {
    private val prefs =
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE,
        )

    fun stateFor(
        runtime: InstalledRuntime,
        tools: List<InstalledGuestTool>,
        layers:
            List<DeployedWindowsRuntimeLayer> =
            emptyList(),
    ): RuntimeProbeEvidenceState {
        val box64Key =
            evidenceKey(
                runtime,
                tools,
                listOf("box64"),
            )
        val wineKey =
            evidenceKey(
                runtime,
                tools,
                listOf("box64", "wine"),
            )
        val d3d11Key =
            graphicsEvidenceKey(
                runtime = runtime,
                tools = tools,
                layers = layers,
                layerId = "dxvk",
            )

        return RuntimeProbeEvidenceState(
            box64SmokePassed =
                box64Key != null &&
                    prefs.getString(
                        KEY_BOX64,
                        null,
                    ) == box64Key,
            wineSmokePassed =
                wineKey != null &&
                    prefs.getString(
                        KEY_WINE,
                        null,
                    ) == wineKey,
            d3d11SmokePassed =
                d3d11Key != null &&
                    prefs.getString(
                        KEY_D3D11,
                        null,
                    ) == d3d11Key,
        )
    }

    fun recordIfValid(
        probe: GuestRuntimeProbe,
        result: ProotExecutionResult,
        runtime: InstalledRuntime,
        tools: List<InstalledGuestTool>,
        layers:
            List<DeployedWindowsRuntimeLayer> =
            emptyList(),
    ): Boolean {
        if (
            !result.passed ||
            result.outputTruncated
        ) {
            return false
        }

        val pair =
            when (probe) {
                GuestRuntimeProbe.BOX64_SMOKE -> {
                    if (
                        !result.output.contains(
                            "POCKETPC_BOX64_SMOKE_OK",
                        ) ||
                        !result.output.contains(
                            "box64_x86_64_smoke=passed",
                        )
                    ) {
                        return false
                    }
                    KEY_BOX64 to
                        evidenceKey(
                            runtime,
                            tools,
                            listOf("box64"),
                        )
                }
                GuestRuntimeProbe.WINE_SMOKE -> {
                    if (
                        !result.output.contains(
                            "POCKETPC_WIN64_SMOKE_OK",
                        ) ||
                        !result.output.contains(
                            "wine_win64_smoke=passed",
                        )
                    ) {
                        return false
                    }
                    KEY_WINE to
                        evidenceKey(
                            runtime,
                            tools,
                            listOf(
                                "box64",
                                "wine",
                            ),
                        )
                }
                GuestRuntimeProbe.D3D11_SMOKE -> {
                    if (
                        !result.output.contains(
                            "POCKETPC_D3D11_SMOKE_OK",
                        ) ||
                        !result.output.contains(
                            "d3d11_dxvk_smoke=passed",
                        )
                    ) {
                        return false
                    }
                    KEY_D3D11 to
                        graphicsEvidenceKey(
                            runtime = runtime,
                            tools = tools,
                            layers = layers,
                            layerId = "dxvk",
                        )
                }
                GuestRuntimeProbe.SHELL,
                GuestRuntimeProbe.ROOTFS,
                GuestRuntimeProbe.TOOLCHAIN ->
                    null
            } ?: return false

        val key =
            pair.second
                ?: return false

        return prefs.edit()
            .putString(
                pair.first,
                key,
            )
            .commit()
    }

    private fun evidenceKey(
        runtime: InstalledRuntime,
        tools: List<InstalledGuestTool>,
        ids: List<String>,
    ): String? {
        val byId =
            tools.associateBy {
                it.manifest.id
            }
        val selected =
            ids.map { id ->
                byId[id]
                    ?: return null
            }

        return digestCanonical(
            buildString {
                appendLine(
                    runtime.manifest
                        .rootfsSha256
                        .lowercase(),
                )
                appendLine(
                    runtime.manifest.id,
                )
                appendLine(
                    runtime.manifest.version,
                )
                selected.forEach { tool ->
                    append(
                        tool.manifest.id,
                    )
                    append('=')
                    append(
                        GuestToolFingerprint.of(
                            tool.manifest,
                        ),
                    )
                    appendLine()
                }
            },
        )
    }

    private fun graphicsEvidenceKey(
        runtime: InstalledRuntime,
        tools: List<InstalledGuestTool>,
        layers:
            List<DeployedWindowsRuntimeLayer>,
        layerId: String,
    ): String? {
        val base =
            evidenceKey(
                runtime,
                tools,
                listOf(
                    "box64",
                    "wine",
                ),
            ) ?: return null
        val layer =
            layers.singleOrNull {
                it.manifest.id ==
                    layerId
            } ?: return null

        return digestCanonical(
            buildString {
                appendLine(base)
                append(
                    layer.manifest.id,
                )
                append('=')
                appendLine(
                    WindowsRuntimeLayerFingerprint
                        .of(
                            layer.manifest,
                        ),
                )
            },
        )
    }

    companion object {
        private const val PREFS =
            "runtime-probe-evidence-v2"
        private const val KEY_BOX64 =
            "box64-smoke-key"
        private const val KEY_WINE =
            "wine-smoke-key"
        private const val KEY_D3D11 =
            "d3d11-smoke-key"
    }
}
