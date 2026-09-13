package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcRuntimeWindowedAttemptGateTest {
    private fun windowedReadyRuntime(): PcRuntimeReadiness =
        PcRuntimeReadinessProbe.assess(
            nativeHost =
                NativeHostStatus(
                    loaded = true,
                    probe = "ok",
                    graphicsProbe = "vulkan=ok",
                    nativeLibraryDir = "/native",
                ),
            substrate =
                ExecutionSubstrateStatus(
                    nativeLibraryDir = "/native",
                    packagedHostReady = true,
                    prootReady = true,
                    components = emptyList(),
                    state = "READY",
                    artifactContractApproved = true,
                    policyDigestsVerified = true,
                    artifactIntegrityVerified = true,
                ),
            installedRuntimeCount = 1,
            preparedRuntimeCount = 1,
            probeEvidence =
                RuntimeProbeEvidenceState(
                    box64SmokePassed = true,
                    wineSmokePassed = true,
                    displayBridgeSmokePassed = true,
                    windowsProcessSmokePassed = true,
                    winePocketPcWindowSmokePassed = true,
                ),
            windowsStateReady = true,
        )

    @Test
    fun windowedGateCanPassWithoutPromotingDxvkGate() {
        val readiness = windowedReadyRuntime()

        assertTrue(readiness.windowedAttemptReady)
        assertFalse(readiness.controlledAttemptReady)
        assertFalse(readiness.executableReady)
    }

    @Test
    fun winrarUsesWindowedGateWhileArbitraryExeKeepsDxvkGate() {
        val readiness = windowedReadyRuntime()
        val winrar =
            PcApplicationTarget(
                uri = "content://downloads/winrar",
                fileName = "winrar-x64.exe",
                sizeBytes = 1L,
            )
        val arbitraryD3d =
            PcApplicationTarget(
                uri = "content://downloads/game",
                fileName = "game.exe",
                sizeBytes = 1L,
            )

        val winrarPlan =
            PcRuntimeExecutionPlanner.build(
                target = winrar,
                readiness = readiness,
                compatibility =
                    PcApplicationCompatibilityProbe.assess(
                        fileName = winrar.fileName,
                        readiness = readiness,
                    ),
            )
        val d3dPlan =
            PcRuntimeExecutionPlanner.build(
                target = arbitraryD3d,
                readiness = readiness,
                compatibility =
                    PcApplicationCompatibilityProbe.assess(
                        fileName = arbitraryD3d.fileName,
                        readiness = readiness,
                    ),
            )

        assertTrue(winrarPlan.attemptEligible)
        assertFalse(winrarPlan.launchEligible)
        assertFalse(d3dPlan.attemptEligible)
        assertFalse(d3dPlan.launchEligible)
    }
}