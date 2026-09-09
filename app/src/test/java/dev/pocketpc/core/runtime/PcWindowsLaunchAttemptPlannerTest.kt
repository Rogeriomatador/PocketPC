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
            "-c",
            args[0],
        )
        assertTrue(
            args[1].contains(
                "reg.exe add " +
                    "'HKCU\\Software\\Wine\\Drivers' " +
                    "/v Graphics /t REG_SZ /d pocketpc /f",
            ),
        )
        assertTrue(
            args[1].contains(
                "exec \"\$box64\" \"\$wine\" \"\$@\"",
            ),
        )
        assertTrue(
            args[1].contains(
                "box64=\"\$1\"",
            ),
        )
        assertTrue(
            args[1].contains(
                "wine=\"\$2\"",
            ),
        )
        assertEquals(
            "pocketpc-windows-launch",
            args[2],
        )
        assertEquals(
            wineArgv,
            args.drop(3),
        )
        assertFalse(
            args[1].contains(target),
        )
        assertFalse(
            args[1].contains(
                "'HKCU\\\\Software",
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
