package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WineGraphicsDriverSelectionTest {
    @Test
    fun wineDriverWindowSmokeSelectsPocketPcGraphicsDriver() {
        val script =
            GuestRuntimeProbe
                .WINE_POCKETPC_WINDOW_SMOKE
                .script

        assertTrue(
            script.contains(
                "reg.exe add " +
                    "'HKCU\\Software\\Wine\\Drivers' " +
                    "/v Graphics /t REG_SZ /d pocketpc /f",
            ),
        )
        assertTrue(
            script.contains(
                "wine_graphics_driver_config=pocketpc",
            ),
        )
        assertFalse(
            script.contains(
                "'HKCU\\\\Software",
            ),
        )
    }

    @Test
    fun d3dPresentSmokeSelectsSamePocketPcGraphicsDriver() {
        val script =
            GuestRuntimeProbe
                .D3D11_PRESENT_SMOKE
                .script

        assertTrue(
            script.contains(
                "reg.exe add " +
                    "'HKCU\\Software\\Wine\\Drivers' " +
                    "/v Graphics /t REG_SZ /d pocketpc /f",
            ),
        )
        assertTrue(
            script.contains(
                "WINEDLLOVERRIDES='d3d11=n;dxgi=n'",
            ),
        )
    }
}
