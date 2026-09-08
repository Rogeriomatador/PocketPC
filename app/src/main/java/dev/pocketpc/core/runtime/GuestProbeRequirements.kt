package dev.pocketpc.core.runtime

object GuestProbeRequirements {
    fun requiredToolIds(
        probe: GuestRuntimeProbe,
    ): Set<String> =
        when (probe) {
            GuestRuntimeProbe.SHELL,
            GuestRuntimeProbe.ROOTFS,
            GuestRuntimeProbe.TOOLCHAIN ->
                emptySet()
            GuestRuntimeProbe.BOX64_SMOKE ->
                setOf("box64")
            GuestRuntimeProbe.WINE_SMOKE,
            GuestRuntimeProbe.D3D11_SMOKE,
            GuestRuntimeProbe.WINDOWS_PROCESS_SMOKE,
            GuestRuntimeProbe.WINSOCK_SMOKE,
            GuestRuntimeProbe.WINMM_AUDIO_API_SMOKE,
            GuestRuntimeProbe.RAW_INPUT_API_SMOKE ->
                setOf("box64", "wine")
        }

    fun requiredWindowsLayerIds(
        probe: GuestRuntimeProbe,
    ): Set<String> =
        when (probe) {
            GuestRuntimeProbe.D3D11_SMOKE ->
                setOf("dxvk")
            else ->
                emptySet()
        }

    fun blockers(
        probe: GuestRuntimeProbe,
        installedToolIds: Set<String>,
        overlayValid: Boolean,
        installedWindowsLayerIds: Set<String> =
            emptySet(),
    ): List<String> {
        val blockers = mutableListOf<String>()
        if (!overlayValid && installedToolIds.isNotEmpty()) {
            blockers += "GUEST_TOOL_OVERLAY_NOT_ATTESTED"
        }
        requiredToolIds(probe)
            .filterNot(installedToolIds::contains)
            .sorted()
            .forEach {
                blockers +=
                    "GUEST_TOOL_REQUIRED_MISSING:" + it
            }
        requiredWindowsLayerIds(probe)
            .filterNot(
                installedWindowsLayerIds::contains,
            )
            .sorted()
            .forEach {
                blockers +=
                    "WINDOWS_LAYER_REQUIRED_MISSING:" +
                        it
            }
        return blockers
    }
}
