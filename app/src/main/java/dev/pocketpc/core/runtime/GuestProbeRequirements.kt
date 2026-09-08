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
            GuestRuntimeProbe.WINE_SMOKE ->
                setOf("box64", "wine")
        }

    fun blockers(
        probe: GuestRuntimeProbe,
        installedToolIds: Set<String>,
        overlayValid: Boolean,
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
        return blockers
    }
}
