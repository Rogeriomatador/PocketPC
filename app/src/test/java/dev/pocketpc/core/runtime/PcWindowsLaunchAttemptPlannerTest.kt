package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcWindowsLaunchAttemptPlannerTest {
    @Test
    fun targetPathRemainsPositionalAndNeverEntersShellSource() {
        val target =
            "/home/pocket/windows-targets/" +
                "abc123-setup;echo-not-shell.exe"
        val wineArgv =
            listOf(
                WineLaunchPlanner.DEFAULT_BOX64,
                WineLaunchPlanner.DEFAULT_WINE,
                target,
            )

        val args =
            PcWindowsLaunchAttemptPlanner
                .shellArguments(
                    wineArgv,
                )

        assertEquals(
            listOf(
                "-c",
                "exec \"\$@\"",
                "pocketpc-windows-launch",
            ) + wineArgv,
            args,
        )
        assertFalse(
            args[1].contains(target),
        )
        assertFalse(
            args[1].contains(
                "reg.exe",
            ),
        )
    }

    @Test
    fun graphicsConfigurationIsSeparateAndFullyPositional() {
        val args =
            PcWindowsLaunchAttemptPlanner
                .graphicsConfigurationShellArguments(
                    box64 =
                        WineLaunchPlanner
                            .DEFAULT_BOX64,
                    wine =
                        WineLaunchPlanner
                            .DEFAULT_WINE,
                )

        assertEquals(
            "-c",
            args[0],
        )
        assertEquals(
            "exec \"\$@\"",
            args[1],
        )
        assertEquals(
            "pocketpc-wine-graphics-config",
            args[2],
        )
        assertEquals(
            WineLaunchPlanner.DEFAULT_BOX64,
            args[3],
        )
        assertEquals(
            WineLaunchPlanner.DEFAULT_WINE,
            args[4],
        )
        assertEquals(
            "reg.exe",
            args[5],
        )
        assertEquals(
            "add",
            args[6],
        )
        assertEquals(
            "HKCU\\Software\\Wine\\Drivers",
            args[7],
        )
        assertEquals(
            listOf(
                "/v",
                "Graphics",
                "/t",
                "REG_SZ",
                "/d",
                "pocketpc",
                "/f",
            ),
            args.drop(8),
        )
        assertFalse(
            args[1].contains(
                "HKCU",
            ),
        )
        assertFalse(
            args[1].contains(
                "pocketpc",
            ),
        )
    }

    @Test
    fun materializedTargetNameRemovesPathSeparatorsAndControlCharacters() {
        val sanitized =
            PcApplicationTargetMaterializer
                .sanitizeFileName(
                    "../folder\\setup\n.exe",
                )

        assertTrue(sanitized.isNotBlank())
        assertFalse('/' in sanitized)
        assertFalse('\\' in sanitized)
        assertFalse('\n' in sanitized)
    }
}
