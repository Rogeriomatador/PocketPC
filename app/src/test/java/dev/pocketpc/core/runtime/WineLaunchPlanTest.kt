package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WineLaunchPlanTest {
    @Test
    fun launchRemainsBlockedUntilBox64AndWineAreValidated() {
        val root = Files.createTempDirectory("pocketpc-wine-plan-").toFile()
        try {
            val prefix = WindowsPrefixPlanner.plan(root, "default")
            val plan =
                WineLaunchPlanner.build(
                    prefixPlan = prefix,
                    windowsExecutable = "C:\\Program Files\\Example\\example.exe",
                    windowsArgs = listOf("--smoke"),
                    box64RuntimeValidated = false,
                    wineRuntimeValidated = false,
                )

            assertFalse(plan.ready)
            assertTrue(plan.argv.isEmpty())
            assertTrue(plan.blockers.contains("BOX64_RUNTIME_NOT_VALIDATED"))
            assertTrue(plan.blockers.contains("WINE_RUNTIME_NOT_VALIDATED"))
            assertEquals(
                "/home/pocket/windows-prefixes/default",
                plan.environment["WINEPREFIX"],
            )
            assertEquals("win64", plan.environment["WINEARCH"])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun validatedComponentsProduceDeterministicGuestArgv() {
        val root = Files.createTempDirectory("pocketpc-wine-plan-ready-").toFile()
        try {
            val prefix = WindowsPrefixPlanner.plan(root, "game")
            val plan =
                WineLaunchPlanner.build(
                    prefixPlan = prefix,
                    windowsExecutable = "C:\\Games\\game.exe",
                    windowsArgs = listOf("-windowed"),
                    box64RuntimeValidated = true,
                    wineRuntimeValidated = true,
                )

            assertTrue(plan.blockers.joinToString(), plan.ready)
            assertEquals(
                listOf(
                    WineLaunchPlanner.DEFAULT_BOX64,
                    WineLaunchPlanner.DEFAULT_WINE,
                    "C:\\Games\\game.exe",
                    "-windowed",
                ),
                plan.argv,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun guestToolPathTraversalIsRejected() {
        val root = Files.createTempDirectory("pocketpc-wine-plan-path-").toFile()
        try {
            val prefix = WindowsPrefixPlanner.plan(root, "default")
            val plan =
                WineLaunchPlanner.build(
                    prefixPlan = prefix,
                    windowsExecutable = "C:\\test.exe",
                    box64RuntimeValidated = true,
                    wineRuntimeValidated = true,
                    box64GuestPath = "/opt/pocketpc/../escape/box64",
                )

            assertFalse(plan.ready)
            assertTrue(plan.blockers.contains("BOX64_GUEST_PATH_INVALID"))
        } finally {
            root.deleteRecursively()
        }
    }
}
