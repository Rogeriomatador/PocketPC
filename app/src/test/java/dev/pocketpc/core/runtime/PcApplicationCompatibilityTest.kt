package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcApplicationCompatibilityTest {
    @Test
    fun robloxInstallerIsRecognizedButRemainsFailClosed() {
        val readiness =
            PcRuntimeReadiness(
                target = "Windows x64 em Android ARM64",
                stages =
                    listOf(
                        PcRuntimeStage(
                            id = "native-arm64-host",
                            label = "Host nativo ARM64",
                            state = PcRuntimeStageState.READY,
                            detail = "ok",
                        ),
                        PcRuntimeStage(
                            id = "x86-64-translation",
                            label = "Tradução x86_64 → ARM64",
                            state =
                                PcRuntimeStageState.NOT_IMPLEMENTED,
                            detail = "missing",
                        ),
                    ),
                executableReady = false,
            )

        val result =
            PcApplicationCompatibilityProbe.assess(
                fileName = "RobloxPlayerInstaller.exe",
                readiness = readiness,
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
            "Tradução x86_64 → ARM64" in
                result.missingRuntimeStages
        )
    }

    @Test
    fun readyBaseStillRequiresApplicationValidation() {
        val readiness =
            PcRuntimeReadiness(
                target = "Windows x64 em Android ARM64",
                stages =
                    listOf(
                        PcRuntimeStage(
                            id = "all",
                            label = "Runtime base",
                            state = PcRuntimeStageState.READY,
                            detail = "ok",
                        )
                    ),
                executableReady = true,
            )

        val result =
            PcApplicationCompatibilityProbe.assess(
                fileName = "setup.exe",
                readiness = readiness,
            )

        assertEquals(
            PcApplicationCompatibilityKindOrWindows(),
            result.kind,
        )
        assertTrue(result.runtimeReady)
        assertFalse(result.applicationValidated)
        assertEquals(
            PcApplicationCompatibilityState
                .RUNTIME_READY_APP_UNVALIDATED,
            result.state,
        )
    }

    private fun PcApplicationCompatibilityKindOrWindows():
        PcApplicationKind =
        PcApplicationKind.WINDOWS_INSTALLER
}
