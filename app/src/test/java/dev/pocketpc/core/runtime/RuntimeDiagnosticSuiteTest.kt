package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDiagnosticSuiteTest {
    @Test
    fun suiteOrderFollowsRuntimeDependencyChain() {
        assertEquals(
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
            ),
            RuntimeDiagnosticSuite
                .orderedProbes,
        )
    }

    @Test
    fun suiteContainsNoDuplicates() {
        assertEquals(
            RuntimeDiagnosticSuite
                .orderedProbes.size,
            RuntimeDiagnosticSuite
                .orderedProbes
                .distinct()
                .size,
        )
    }

    @Test
    fun bridgeRunsBeforeWineAndGraphics() {
        val probes =
            RuntimeDiagnosticSuite
                .orderedProbes
        val bridge =
            probes.indexOf(
                GuestRuntimeProbe
                    .DISPLAY_BRIDGE_SMOKE,
            )
        val wine =
            probes.indexOf(
                GuestRuntimeProbe
                    .WINE_SMOKE,
            )
        val graphics =
            probes.indexOf(
                GuestRuntimeProbe
                    .D3D11_SMOKE,
            )
        val wineWindow =
            probes.indexOf(
                GuestRuntimeProbe
                    .WINE_POCKETPC_WINDOW_SMOKE,
            )

        assertTrue(bridge > 0)
        assertTrue(bridge < wine)
        assertTrue(wine < wineWindow)
        assertTrue(wineWindow < graphics)
    }

    @Test
    fun presentProbeIsExplicitlyBlockedUntilWsiBackendExists() {
        assertFalse(
            RuntimeDiagnosticSuite
                .orderedProbes
                .contains(
                    GuestRuntimeProbe
                        .D3D11_PRESENT_SMOKE,
                ),
        )
        assertTrue(
            RuntimeDiagnosticSuite
                .structurallyBlockedProbes
                .contains(
                    GuestRuntimeProbe
                        .D3D11_PRESENT_SMOKE,
                ),
        )
    }
}
