package dev.pocketpc.core.runtime

import android.content.Context
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

data class RuntimeProbeEvidenceState(
    val box64SmokePassed: Boolean,
    val wineSmokePassed: Boolean,
    val displayBridgeSmokePassed: Boolean = false,
    val d3d11SmokePassed: Boolean = false,
    val graphicsPresentationSmokePassed: Boolean = false,
    val windowsProcessSmokePassed: Boolean = false,
    val winsockSmokePassed: Boolean = false,
    val winmmAudioApiSmokePassed: Boolean = false,
    val rawInputApiSmokePassed: Boolean = false,
    val winePocketPcWindowSmokePassed:
        Boolean = false,
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
                    .sortedBy {
                        it.path
                    }
                    .forEach {
                        appendLine(
                            "${it.path}|" +
                                "${it.bytes}|" +
                                "${it.sha256}|" +
                                "${it.executable}",
                        )
                    }
            },
        )
}

object WindowsRuntimeLayerFingerprint {
    fun of(
        manifest:
            WindowsRuntimeLayerManifest,
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
                    .forEach {
                        appendLine(
                            "${it.path}|" +
                                "${it.destinationName}|" +
                                "${it.bytes}|" +
                                "${it.sha256}",
                        )
                    }
            },
        )
}

private fun digestCanonical(
    text: String,
): String =
    Sha256.digest(
        ByteArrayInputStream(
            text.toByteArray(
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
        val box =
            evidenceKey(
                runtime,
                tools,
                listOf("box64"),
            )
        val full =
            RuntimeExecutionIdentity.of(
                runtime,
                tools,
                layers,
            )
        val wine =
            evidenceKey(
                runtime,
                tools,
                listOf(
                    "box64",
                    "wine",
                ),
            )
        val d3d =
            graphicsKey(
                runtime,
                tools,
                layers,
                "dxvk",
            )

        fun match(
            key: String,
            value: String?,
        ): Boolean =
            value != null &&
                prefs.getString(
                    key,
                    null,
                ) == value

        return RuntimeProbeEvidenceState(
            box64SmokePassed =
                match(
                    KEY_BOX64,
                    box,
                ),
            wineSmokePassed =
                match(
                    KEY_WINE,
                    wine,
                ),
            displayBridgeSmokePassed =
                match(
                    KEY_DISPLAY_BRIDGE,
                    full,
                ),
            d3d11SmokePassed =
                match(
                    KEY_D3D11,
                    d3d,
                ),
            graphicsPresentationSmokePassed =
                match(
                    KEY_D3D11_PRESENT,
                    d3d,
                ),
            windowsProcessSmokePassed =
                match(
                    KEY_WINDOWS_PROCESS,
                    wine,
                ),
            winsockSmokePassed =
                match(
                    KEY_WINSOCK,
                    wine,
                ),
            winmmAudioApiSmokePassed =
                match(
                    KEY_WINMM_AUDIO,
                    wine,
                ),
            rawInputApiSmokePassed =
                match(
                    KEY_RAW_INPUT,
                    wine,
                ),
            winePocketPcWindowSmokePassed =
                match(
                    KEY_WINE_POCKETPC_WINDOW,
                    full,
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

        fun both(
            first: String,
            second: String,
        ): Boolean =
            result.output.contains(first) &&
                result.output.contains(second)

        fun all(
            vararg markers: String,
        ): Boolean =
            markers.all(
                result.output::contains,
            )

        val pair =
            when (probe) {
                GuestRuntimeProbe
                    .BOX64_SMOKE ->
                    if (
                        both(
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

                GuestRuntimeProbe
                    .DISPLAY_BRIDGE_SMOKE ->
                    if (
                        all(
                            "POCKETPC_DISPLAY_BRIDGE_SMOKE_OK",
                            "display_bridge_smoke=passed",
                            "POCKETPC_DISPLAY_BRIDGE_HOST_AUTH_OK",
                            "POCKETPC_DISPLAY_BRIDGE_HOST_WINDOW_OK",
                            "POCKETPC_DISPLAY_BRIDGE_POINTER_OK",
                            "POCKETPC_DISPLAY_BRIDGE_KEY_OK",
                            "POCKETPC_DISPLAY_BRIDGE_FRAME_WRITTEN_OK",
                            "POCKETPC_DISPLAY_BRIDGE_FRAME_ACK_OK",
                            "POCKETPC_DISPLAY_BRIDGE_HOST_FRAMEBUFFER_OK",
                            "POCKETPC_DISPLAY_BRIDGE_HOST_ROUNDTRIP_OK",
                        )
                    ) {
                        KEY_DISPLAY_BRIDGE to
                            RuntimeExecutionIdentity
                                .of(
                                    runtime,
                                    tools,
                                    layers,
                                )
                    } else {
                        null
                    }

                GuestRuntimeProbe
                    .WINE_SMOKE ->
                    if (
                        both(
                            "POCKETPC_WIN64_SMOKE_OK",
                            "wine_win64_smoke=passed",
                        )
                    ) {
                        KEY_WINE to
                            evidenceKey(
                                runtime,
                                tools,
                                listOf(
                                    "box64",
                                    "wine",
                                ),
                            )
                    } else {
                        null
                    }

                GuestRuntimeProbe
                    .WINE_POCKETPC_WINDOW_SMOKE ->
                    if (
                        all(
                            "POCKETPC_WINE_DRIVER_WINDOW_OK",
                            "POCKETPC_WINE_DRIVER_PAINT_OK",
                            "POCKETPC_WINE_DRIVER_POINTER_OK",
                            "POCKETPC_WINE_DRIVER_KEY_OK",
                            "POCKETPC_WINE_DRIVER_INPUT_OK",
                            "POCKETPC_WINE_DRIVER_SMOKE_OK",
                            "wine_pocketpc_driver_smoke=passed",
                            "POCKETPC_DISPLAY_BRIDGE_HOST_AUTH_OK",
                            "POCKETPC_DISPLAY_BRIDGE_HOST_WINDOW_OK",
                            "POCKETPC_DISPLAY_BRIDGE_HOST_FRAMEBUFFER_OK",
                            "POCKETPC_DISPLAY_BRIDGE_HOST_INPUT_OK",
                            "POCKETPC_WINE_DRIVER_HOST_FRAME_OK",
                            "POCKETPC_WINE_DRIVER_HOST_INPUT_OK",
                            "POCKETPC_DISPLAY_BRIDGE_HOST_ROUNDTRIP_OK",
                        )
                    ) {
                        KEY_WINE_POCKETPC_WINDOW to
                            RuntimeExecutionIdentity
                                .of(
                                    runtime,
                                    tools,
                                    layers,
                                )
                    } else {
                        null
                    }

                GuestRuntimeProbe
                    .D3D11_SMOKE ->
                    if (
                        both(
                            "POCKETPC_D3D11_SMOKE_OK",
                            "d3d11_dxvk_smoke=passed",
                        )
                    ) {
                        KEY_D3D11 to
                            graphicsKey(
                                runtime,
                                tools,
                                layers,
                                "dxvk",
                            )
                    } else {
                        null
                    }

                GuestRuntimeProbe
                    .D3D11_PRESENT_SMOKE ->
                    if (
                        both(
                            "POCKETPC_D3D11_PRESENT_SMOKE_OK",
                            "d3d11_present_smoke=passed",
                        )
                    ) {
                        KEY_D3D11_PRESENT to
                            graphicsKey(
                                runtime,
                                tools,
                                layers,
                                "dxvk",
                            )
                    } else {
                        null
                    }

                GuestRuntimeProbe
                    .WINDOWS_PROCESS_SMOKE ->
                    if (
                        both(
                            "POCKETPC_WIN_PROCESS_IPC_SMOKE_OK",
                            "windows_process_ipc_smoke=passed",
                        )
                    ) {
                        KEY_WINDOWS_PROCESS to
                            evidenceKey(
                                runtime,
                                tools,
                                listOf(
                                    "box64",
                                    "wine",
                                ),
                            )
                    } else {
                        null
                    }

                GuestRuntimeProbe
                    .WINSOCK_SMOKE ->
                    if (
                        both(
                            "POCKETPC_WINSOCK_SMOKE_OK",
                            "winsock_smoke=passed",
                        )
                    ) {
                        KEY_WINSOCK to
                            evidenceKey(
                                runtime,
                                tools,
                                listOf(
                                    "box64",
                                    "wine",
                                ),
                            )
                    } else {
                        null
                    }

                GuestRuntimeProbe
                    .WINMM_AUDIO_API_SMOKE ->
                    if (
                        both(
                            "POCKETPC_WINMM_AUDIO_API_OK",
                            "winmm_audio_api_smoke=passed",
                        )
                    ) {
                        KEY_WINMM_AUDIO to
                            evidenceKey(
                                runtime,
                                tools,
                                listOf(
                                    "box64",
                                    "wine",
                                ),
                            )
                    } else {
                        null
                    }

                GuestRuntimeProbe
                    .RAW_INPUT_API_SMOKE ->
                    if (
                        both(
                            "POCKETPC_RAW_INPUT_API_OK",
                            "raw_input_api_smoke=passed",
                        )
                    ) {
                        KEY_RAW_INPUT to
                            evidenceKey(
                                runtime,
                                tools,
                                listOf(
                                    "box64",
                                    "wine",
                                ),
                            )
                    } else {
                        null
                    }

                GuestRuntimeProbe.SHELL,
                GuestRuntimeProbe.ROOTFS,
                GuestRuntimeProbe.TOOLCHAIN ->
                    null
            } ?: return false

        val value =
            pair.second
                ?: return false
        return prefs.edit()
            .putString(
                pair.first,
                value,
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
            ids.map {
                byId[it]
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
                selected.forEach {
                    appendLine(
                        "${it.manifest.id}=" +
                            GuestToolFingerprint
                                .of(
                                    it.manifest,
                                ),
                    )
                }
            },
        )
    }

    private fun graphicsKey(
        runtime: InstalledRuntime,
        tools: List<InstalledGuestTool>,
        layers:
            List<DeployedWindowsRuntimeLayer>,
        id: String,
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
                it.manifest.id == id
            } ?: return null

        return digestCanonical(
            base +
                "\n" +
                layer.manifest.id +
                "=" +
                WindowsRuntimeLayerFingerprint
                    .of(layer.manifest) +
                "\n",
        )
    }

    companion object {
        private const val
            PREFS =
            "runtime-probe-evidence-v8"
        private const val
            KEY_BOX64 =
            "box64-smoke-key"
        private const val
            KEY_DISPLAY_BRIDGE =
            "display-bridge-smoke-key"
        private const val
            KEY_WINE =
            "wine-smoke-key"
        private const val
            KEY_WINE_POCKETPC_WINDOW =
            "wine-pocketpc-window-smoke-key"
        private const val
            KEY_D3D11 =
            "d3d11-smoke-key"
        private const val
            KEY_D3D11_PRESENT =
            "d3d11-present-smoke-key"
        private const val
            KEY_WINDOWS_PROCESS =
            "windows-process-ipc-smoke-key"
        private const val
            KEY_WINSOCK =
            "winsock-smoke-key"
        private const val
            KEY_WINMM_AUDIO =
            "winmm-audio-api-smoke-key"
        private const val
            KEY_RAW_INPUT =
            "raw-input-api-smoke-key"
    }
}
