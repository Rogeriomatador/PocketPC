package dev.pocketpc.core.runtime

import android.content.Context
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

data class RuntimeProbeEvidenceState(
    val box64SmokePassed: Boolean,
    val displayBridgeSmokePassed: Boolean = false,
    val wineSmokePassed: Boolean,
    val d3d11SmokePassed: Boolean = false,
    val graphicsPresentationSmokePassed: Boolean = false,
    val windowsProcessSmokePassed: Boolean = false,
    val winsockSmokePassed: Boolean = false,
    val winmmAudioApiSmokePassed: Boolean = false,
    val rawInputApiSmokePassed: Boolean = false,
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
        val displayBridgeKey =
            fullRuntimeEvidenceKey(
                runtime,
                tools,
                layers,
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

        fun matches(
            prefKey: String,
            expected: String?,
        ): Boolean =
            expected != null &&
                prefs.getString(
                    prefKey,
                    null,
                ) == expected

        return RuntimeProbeEvidenceState(
            box64SmokePassed =
                matches(
                    KEY_BOX64,
                    box64Key,
                ),
            displayBridgeSmokePassed =
                matches(
                    KEY_DISPLAY_BRIDGE,
                    displayBridgeKey,
                ),
            wineSmokePassed =
                matches(
                    KEY_WINE,
                    wineKey,
                ),
            d3d11SmokePassed =
                matches(
                    KEY_D3D11,
                    d3d11Key,
                ),
            graphicsPresentationSmokePassed =
                matches(
                    KEY_D3D11_PRESENT,
                    d3d11Key,
                ),
            windowsProcessSmokePassed =
                matches(
                    KEY_WINDOWS_PROCESS,
                    wineKey,
                ),
            winsockSmokePassed =
                matches(
                    KEY_WINSOCK,
                    wineKey,
                ),
            winmmAudioApiSmokePassed =
                matches(
                    KEY_WINMM_AUDIO,
                    wineKey,
                ),
            rawInputApiSmokePassed =
                matches(
                    KEY_RAW_INPUT,
                    wineKey,
                ),
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
                GuestRuntimeProbe.BOX64_SMOKE ->
                    if (
                        hasBoth(
                            result,
                            "POCKETPC_BOX64_SMOKE_OK",
                            "box64_x86_64_smoke=passed",
                        )
                    ) {
                        KEY_BOX64 to
                            evidenceKey(
                                runtime,
                                tools,
                                listOf("box64"),
                            )
                    } else {
                        null
                    }

                GuestRuntimeProbe.DISPLAY_BRIDGE_SMOKE ->
                    if (
                        result.output.contains(
                            "POCKETPC_DISPLAY_BRIDGE_SMOKE_OK",
                        ) &&
                        result.output.contains(
                            "display_bridge_smoke=passed",
                        ) &&
                        result.output.contains(
                            "POCKETPC_DISPLAY_BRIDGE_HOST_AUTH_OK",
                        )
                    ) {
                        KEY_DISPLAY_BRIDGE to
                            fullRuntimeEvidenceKey(
                                runtime,
                                tools,
                                layers,
                            )
                    } else {
                        null
                    }

                GuestRuntimeProbe.WINE_SMOKE ->
                    if (
                        hasBoth(
                            result,
                            "POCKETPC_WIN64_SMOKE_OK",
                            "wine_win64_smoke=passed",
                        )
                    ) {
                        KEY_WINE to
                            wineEvidenceKey(
                                runtime,
                                tools,
                            )
                    } else {
                        null
                    }

                GuestRuntimeProbe.D3D11_SMOKE ->
                    if (
                        hasBoth(
                            result,
                            "POCKETPC_D3D11_SMOKE_OK",
                            "d3d11_dxvk_smoke=passed",
                        )
                    ) {
                        KEY_D3D11 to
                            graphicsEvidenceKey(
                                runtime = runtime,
                                tools = tools,
                                layers = layers,
                                layerId = "dxvk",
                            )
                    } else {
                        null
                    }

                GuestRuntimeProbe.D3D11_PRESENT_SMOKE ->
                    if (
                        hasBoth(
                            result,
                            "POCKETPC_D3D11_PRESENT_SMOKE_OK",
                            "d3d11_present_smoke=passed",
                        )
                    ) {
                        KEY_D3D11_PRESENT to
                            graphicsEvidenceKey(
                                runtime = runtime,
                                tools = tools,
                                layers = layers,
                                layerId = "dxvk",
                            )
                    } else {
                        null
                    }

                GuestRuntimeProbe.WINDOWS_PROCESS_SMOKE ->
                    if (
                        hasBoth(
                            result,
                            "POCKETPC_WIN_PROCESS_IPC_SMOKE_OK",
                            "windows_process_ipc_smoke=passed",
                        )
                    ) {
                        KEY_WINDOWS_PROCESS to
                            wineEvidenceKey(
                                runtime,
                                tools,
                            )
                    } else {
                        null
                    }

                GuestRuntimeProbe.WINSOCK_SMOKE ->
                    if (
                        hasBoth(
                            result,
                            "POCKETPC_WINSOCK_SMOKE_OK",
                            "winsock_smoke=passed",
                        )
                    ) {
                        KEY_WINSOCK to
                            wineEvidenceKey(
                                runtime,
                                tools,
                            )
                    } else {
                        null
                    }

                GuestRuntimeProbe.WINMM_AUDIO_API_SMOKE ->
                    if (
                        hasBoth(
                            result,
                            "POCKETPC_WINMM_AUDIO_API_OK",
                            "winmm_audio_api_smoke=passed",
                        )
                    ) {
                        KEY_WINMM_AUDIO to
                            wineEvidenceKey(
                                runtime,
                                tools,
                            )
                    } else {
                        null
                    }

                GuestRuntimeProbe.RAW_INPUT_API_SMOKE ->
                    if (
                        hasBoth(
                            result,
                            "POCKETPC_RAW_INPUT_API_OK",
                            "raw_input_api_smoke=passed",
                        )
                    ) {
                        KEY_RAW_INPUT to
                            wineEvidenceKey(
                                runtime,
                                tools,
                            )
                    } else {
                        null
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

    private fun hasBoth(
        result: ProotExecutionResult,
        first: String,
        second: String,
    ): Boolean =
        result.output.contains(first) &&
            result.output.contains(second)

    private fun wineEvidenceKey(
        runtime: InstalledRuntime,
        tools: List<InstalledGuestTool>,
    ): String? =
        evidenceKey(
            runtime,
            tools,
            listOf(
                "box64",
                "wine",
            ),
        )

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

    private fun fullRuntimeEvidenceKey(
        runtime: InstalledRuntime,
        tools: List<InstalledGuestTool>,
        layers:
            List<DeployedWindowsRuntimeLayer>,
    ): String =
        RuntimeExecutionIdentity.of(
            runtime = runtime,
            tools = tools,
            layers = layers,
        )

    private fun graphicsEvidenceKey(
        runtime: InstalledRuntime,
        tools: List<InstalledGuestTool>,
        layers:
            List<DeployedWindowsRuntimeLayer>,
        layerId: String,
    ): String? {
        val base =
            wineEvidenceKey(
                runtime,
                tools,
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
            "runtime-probe-evidence-v3"
        private const val KEY_BOX64 =
            "box64-smoke-key"
        private const val KEY_DISPLAY_BRIDGE =
            "display-bridge-smoke-key"
        private const val KEY_WINE =
            "wine-smoke-key"
        private const val KEY_D3D11 =
            "d3d11-smoke-key"
        private const val KEY_D3D11_PRESENT =
            "d3d11-present-smoke-key"
        private const val KEY_WINDOWS_PROCESS =
            "windows-process-ipc-smoke-key"
        private const val KEY_WINSOCK =
            "winsock-smoke-key"
        private const val KEY_WINMM_AUDIO =
            "winmm-audio-api-smoke-key"
        private const val KEY_RAW_INPUT =
            "raw-input-api-smoke-key"
    }
}
