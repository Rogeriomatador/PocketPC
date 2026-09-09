package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcRuntimeExecutionPlannerTest {
    @Test
    fun robloxTargetStopsAtFirstMissingRuntimeGate() {
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
                            detail = "Box64 ainda não integrado.",
                        ),
                    ),
                executableReady = false,
            )
        val target =
            PcApplicationTarget(
                uri = "content://downloads/roblox",
                fileName = "RobloxPlayerInstaller.exe",
                sizeBytes = 13_000_000L,
            )
        val compatibility =
            PcApplicationCompatibilityProbe.assess(
                fileName = target.fileName,
                readiness = readiness,
            )

        val plan =
            PcRuntimeExecutionPlanner.build(
                target = target,
                readiness = readiness,
                compatibility = compatibility,
            )

        assertFalse(plan.launchEligible)
        assertTrue(
            plan.nextAction.contains(
                "Tradução x86_64",
            )
        )
    }

    @Test
    fun readyRuntimeStillRequiresApplicationIntegration() {
        val readiness =
            PcRuntimeReadiness(
                target = "Windows x64 em Android ARM64",
                stages =
                    listOf(
                        PcRuntimeStage(
                            id = "runtime",
                            label = "Runtime base",
                            state = PcRuntimeStageState.READY,
                            detail = "ok",
                        )
                    ),
                executableReady = true,
            )
        val target =
            PcApplicationTarget(
                uri = "content://downloads/setup",
                fileName = "setup.exe",
                sizeBytes = 1L,
            )
        val compatibility =
            PcApplicationCompatibilityProbe.assess(
                fileName = target.fileName,
                readiness = readiness,
            )

        val plan =
            PcRuntimeExecutionPlanner.build(
                target = target,
                readiness = readiness,
                compatibility = compatibility,
            )

        assertFalse(plan.launchEligible)
        assertTrue(
            plan.nextAction.contains("integração")
        )
    }
    @Test
    fun controlledAttemptDoesNotPromoteApplicationValidation() {
        val readiness =
            PcRuntimeReadiness(
                target = "Windows x64 em Android ARM64",
                stages =
                    listOf(
                        PcRuntimeStage(
                            id = "runtime",
                            label = "Runtime base",
                            state = PcRuntimeStageState.READY,
                            detail = "ok",
                        )
                    ),
                executableReady = false,
                controlledAttemptReady = true,
            )
        val target =
            PcApplicationTarget(
                uri = "content://downloads/setup",
                fileName = "setup.exe",
                sizeBytes = 1L,
            )
        val compatibility =
            PcApplicationCompatibilityProbe.assess(
                fileName = target.fileName,
                readiness = readiness,
            )

        val plan =
            PcRuntimeExecutionPlanner.build(
                target = target,
                readiness = readiness,
                compatibility = compatibility,
            )

        assertTrue(plan.attemptEligible)
        assertFalse(plan.launchEligible)
        assertFalse(
            compatibility.applicationValidated,
        )
        assertTrue(
            plan.nextAction.contains(
                "UNVALIDATED",
            )
        )
    }

}
