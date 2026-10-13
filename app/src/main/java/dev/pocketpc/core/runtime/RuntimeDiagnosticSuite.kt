package dev.pocketpc.core.runtime

object RuntimeDiagnosticSuite {
    /**
     * Device-validation path that can be completed before DXVK/Vulkan WSI.
     * A PASS here is enough to advance PRoot -> Box64 -> Wine -> PocketPC GDI
     * window/process/input/API evidence, but it must never be promoted to D3D
     * or Roblox compatibility.
     */
    val coreProbes:
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
        )

    /**
     * Graphics probes intentionally remain outside the core device gate.
     * They require their own runtime layers/evidence.
     */
    val graphicsProbes:
        List<GuestRuntimeProbe> =
        listOf(
            GuestRuntimeProbe.D3D11_SMOKE,
        )

    val orderedProbes:
        List<GuestRuntimeProbe> =
        coreProbes + graphicsProbes

    val structurallyBlockedProbes:
        Set<GuestRuntimeProbe> =
        setOf(
            GuestRuntimeProbe
                .D3D11_PRESENT_SMOKE,
        )

    init {
        require(
            coreProbes.distinct().size ==
                coreProbes.size,
        ) {
            "RUNTIME_DIAGNOSTIC_CORE_DUPLICATE_PROBE"
        }
        require(
            graphicsProbes.distinct().size ==
                graphicsProbes.size,
        ) {
            "RUNTIME_DIAGNOSTIC_GRAPHICS_DUPLICATE_PROBE"
        }
        require(
            coreProbes.none {
                it in graphicsProbes
            },
        ) {
            "RUNTIME_DIAGNOSTIC_CORE_GRAPHICS_OVERLAP"
        }
        require(
            orderedProbes.distinct().size ==
                orderedProbes.size,
        ) {
            "RUNTIME_DIAGNOSTIC_SUITE_DUPLICATE_PROBE"
        }
        require(
            coreProbes.first() ==
                GuestRuntimeProbe.SHELL,
        )
        require(
            coreProbes.last() ==
                GuestRuntimeProbe
                    .RAW_INPUT_API_SMOKE,
        )
        require(
            graphicsProbes.first() ==
                GuestRuntimeProbe.D3D11_SMOKE,
        )
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
