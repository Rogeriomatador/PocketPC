package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcWindowsLaunchAttemptPlannerTest {
    @Test
    fun workingDirectoryAndArgumentsActuallyRemainLiteralInHostShellFixture() {
        val root = java.nio.file.Files.createTempDirectory("pocketpc-positional-").toFile()
        try {
            val directory = java.io.File(root, "portable $(touch marker); space").apply { mkdirs() }
            val payload = "; touch marker"
            val args = PcWindowsLaunchAttemptPlanner.shellArguments(
                listOf("/bin/sh", "-c", "printf 'directory=%s\\n' \"\$PWD\"; printf 'argument=%s\\n' \"\$1\"", "fixture", payload),
                workingDirectory = directory.path,
            )
            val process = ProcessBuilder(listOf("/bin/sh") + args).redirectErrorStream(true).start()
            assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.exitValue())
            assertTrue(output.contains("directory=${directory.path}"))
            assertTrue(output.contains("argument=$payload"))
            assertFalse(java.io.File(directory, "marker").exists())
            assertFalse(java.io.File(root, "marker").exists())
        } finally { root.deleteRecursively() }
    }

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
    fun gdiProfileDoesNotRequireDxvkOnlyRuntimeGates() {
        val blockers =
            PcWindowsLaunchAttemptPlanner
                .graphicsRuntimeBlockers(
                    profile =
                        PcApplicationGraphicsProfile
                            .WINDOWED_GDI,
                    evidence =
                        RuntimeProbeEvidenceState(
                            box64SmokePassed = false,
                            wineSmokePassed = false,
                        ),
                    deployedLayers =
                        emptyList(),
                )

        assertTrue(blockers.isEmpty())
    }

    @Test
    fun d3dProfileRemainsFailClosedBehindGraphicsRuntimeGates() {
        val blockers =
            PcWindowsLaunchAttemptPlanner
                .graphicsRuntimeBlockers(
                    profile =
                        PcApplicationGraphicsProfile
                            .D3D_DXVK,
                    evidence =
                        RuntimeProbeEvidenceState(
                            box64SmokePassed = false,
                            wineSmokePassed = false,
                        ),
                    deployedLayers =
                        emptyList(),
                )

        assertTrue(
            "D3D11_RUNTIME_NOT_VALIDATED" in
                blockers,
        )
        assertTrue(
            PocketPcVulkanWsiContract.blocker in
                blockers,
        )
        assertTrue(
            "GRAPHICS_PRESENTATION_NOT_VALIDATED" in
                blockers,
        )
        assertTrue(
            "DXVK_LAYER_NOT_DEPLOYED" in
                blockers,
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
