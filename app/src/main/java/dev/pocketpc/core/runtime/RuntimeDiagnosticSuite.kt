package dev.pocketpc.core.runtime

object RuntimeDiagnosticSuite {
    val orderedProbes:
        List<GuestRuntimeProbe> =
        listOf(
            GuestRuntimeProbe.SHELL,
            GuestRuntimeProbe.ROOTFS,
            GuestRuntimeProbe.TOOLCHAIN,
            GuestRuntimeProbe.BOX64_SMOKE,
            GuestRuntimeProbe.DISPLAY_BRIDGE_SMOKE,
            GuestRuntimeProbe.WINE_SMOKE,
            GuestRuntimeProbe.WINE_POCKETPC_WINDOW_SMOKE,
            GuestRuntimeProbe.WINDOWS_PROCESS_SMOKE,
            GuestRuntimeProbe.WINSOCK_SMOKE,
            GuestRuntimeProbe.WINMM_AUDIO_API_SMOKE,
            GuestRuntimeProbe.RAW_INPUT_API_SMOKE,
            GuestRuntimeProbe.D3D11_SMOKE,
        )

    val structurallyBlockedProbes:
        Set<GuestRuntimeProbe> =
        setOf(
            GuestRuntimeProbe
                .D3D11_PRESENT_SMOKE,
        )

    init {
        require(
            orderedProbes.distinct().size ==
                orderedProbes.size,
        ) {
            "RUNTIME_DIAGNOSTIC_SUITE_DUPLICATE_PROBE"
        }
        require(
            orderedProbes.first() ==
                GuestRuntimeProbe.SHELL,
        )
        require(
            orderedProbes.last() ==
                GuestRuntimeProbe
                    .D3D11_SMOKE,
        )
        require(
            GuestRuntimeProbe
                .D3D11_PRESENT_SMOKE in
                structurallyBlockedProbes,
        )
    }
}
