package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RobloxPcCompatibilityTest {
    private val readyRuntime =
        PcRuntimeReadiness(
            target = "Windows x64 em Android ARM64",
            stages =
                listOf(
                    PcRuntimeStage(
                        id = "base",
                        label = "Runtime base",
                        state = PcRuntimeStageState.READY,
                        detail = "ok",
                    ),
                ),
            executableReady = false,
            controlledAttemptReady = true,
        )

    @Test
    fun storePackageRemainsBlockedEvenWhenBaseRuntimeIsReady() {
        val result =
            PcApplicationCompatibilityProbe.assess(
                fileName = "Roblox-Windows.msixbundle",
                readiness = readyRuntime,
            )

        assertEquals(
            PcApplicationKind.ROBLOX_DESKTOP,
            result.kind,
        )
        assertEquals(
            PcApplicationCompatibilityState.RUNTIME_BLOCKED,
            result.state,
        )
        assertFalse(result.runtimeReady)
        assertFalse(result.applicationValidated)
        assertTrue(
            result.detail.contains("MSIX/AppX"),
        )
    }

    @Test
    fun arbitraryRobloxNameDoesNotBecomeRecognizedPlayer() {
        val artifact =
            RobloxWindowsArtifactClassifier
                .classify("RobloxHelper.exe")
        val result =
            PcApplicationCompatibilityProbe.assess(
                fileName = "RobloxHelper.exe",
                readiness = readyRuntime,
            )

        assertEquals(
            RobloxWindowsArtifactKind.UNKNOWN_ROBLOX,
            artifact.kind,
        )
        assertFalse(artifact.recognized)
        assertEquals(
            PcApplicationKind.WINDOWS_INSTALLER,
            result.kind,
        )
        assertFalse(result.applicationValidated)
        assertTrue(
            result.detail.contains(
                "não corresponde",
                ignoreCase = true,
            ),
        )
    }
}
