package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RobloxLaunchReadinessTest {
    private fun allRuntimeEvidence() =
        RuntimeProbeEvidenceState(
            box64SmokePassed = true,
            wineSmokePassed = true,
            displayBridgeSmokePassed = true,
            d3d11SmokePassed = true,
            graphicsPresentationSmokePassed = true,
            windowsProcessSmokePassed = true,
            winsockSmokePassed = true,
            winmmAudioApiSmokePassed = true,
            rawInputApiSmokePassed = true,
            winePocketPcWindowSmokePassed = true,
        )

    private fun installedRoblox(): RobloxInstallationDiscovery {
        val installation =
            RobloxPlayerInstallation(
                versionDirectoryName = "version-test",
                hostExecutable =
                    File("/tmp/RobloxPlayerBeta.exe"),
                windowsExecutable =
                    "C:\\users\\pocket\\AppData\\Local\\Roblox\\Versions\\" +
                        "version-test\\RobloxPlayerBeta.exe",
                bytes = 123L,
                modifiedAtMillis = 1L,
            )
        return RobloxInstallationDiscovery(
            versionsRoot = File("/tmp/Versions"),
            installations = listOf(installation),
            selected = installation,
            blockers = emptyList(),
        )
    }

    private fun readyRuntime() =
        PcRuntimeReadiness(
            target = "Windows x64 em Android ARM64",
            stages = emptyList(),
            executableReady = false,
            controlledAttemptReady = true,
        )

    @Test
    fun vulkanWsiKeepsRobloxFailClosedEvenWhenOtherEvidenceIsTrue() {
        val result =
            RobloxLaunchReadinessProbe.assess(
                runtimeReadiness = readyRuntime(),
                probeEvidence = allRuntimeEvidence(),
                installation = installedRoblox(),
            )

        assertFalse(PocketPcVulkanWsiContract.implemented)
        assertEquals(
            RobloxLaunchReadinessState.RUNTIME_BLOCKED,
            result.state,
        )
        assertFalse(result.controlledAttemptReady)
        assertFalse(result.integrationSmokePassed)
        assertFalse(result.gameplayValidated)
        assertTrue(
            result.blockers.contains(
                PocketPcVulkanWsiContract.blocker,
            ),
        )
    }

    @Test
    fun guestGraphicsTransportHasDedicatedRobloxBlocker() {
        val hostOnlyFoundation =
            PocketPcVulkanWsiFoundationStatus(
                nativeHostLoaded = true,
                sameProcessTransportStructurallyReady = true,
                crossProcessTransportVerified = true,
                distinctProcessesObserved = true,
                readyForWsiImplementation = false,
                blockers =
                    listOf(
                        PocketPcVulkanWsiFoundationProbe
                            .BLOCKER_GUEST_GRAPHICS_TRANSPORT,
                    ),
                guestGraphicsTransportReady = false,
            )

        val result =
            RobloxLaunchReadinessProbe.assess(
                runtimeReadiness = readyRuntime(),
                probeEvidence = allRuntimeEvidence(),
                installation = installedRoblox(),
                wsiFoundation = hostOnlyFoundation,
            )

        assertEquals(
            RobloxLaunchReadinessState.RUNTIME_BLOCKED,
            result.state,
        )
        assertFalse(result.controlledAttemptReady)
        assertTrue(
            result.blockers.contains(
                RobloxLaunchReadinessProbe
                    .BLOCKER_GUEST_GRAPHICS_TRANSPORT,
            ),
        )
    }

    @Test
    fun noInstallationCannotBePromotedByRuntimeEvidence() {
        val result =
            RobloxLaunchReadinessProbe.assess(
                runtimeReadiness =
                    PcRuntimeReadiness(
                        target = "test",
                        stages = emptyList(),
                        executableReady = true,
                        controlledAttemptReady = true,
                    ),
                probeEvidence = allRuntimeEvidence(),
                installation =
                    RobloxInstallationDiscovery(
                        versionsRoot = File("/tmp/Versions"),
                        installations = emptyList(),
                        selected = null,
                        blockers =
                            listOf(
                                "ROBLOX_CLASSIC_PLAYER_NOT_INSTALLED",
                            ),
                    ),
            )

        assertEquals(
            RobloxLaunchReadinessState.NOT_INSTALLED,
            result.state,
        )
        assertFalse(result.controlledAttemptReady)
        assertFalse(result.gameplayValidated)
    }

    @Test
    fun applicationSmokeRequiresAllSignalsAndMinimumStableSession() {
        val complete =
            RobloxRuntimeEvidence(
                playerProcessStarted = true,
                playerWindowPresented = true,
                d3d11PresentObserved = true,
                externalNetworkObserved = true,
                audioOutputObserved = true,
                pointerInputObserved = true,
                keyboardInputObserved = true,
                stableSessionMillis =
                    RobloxRuntimeEvidence
                        .MIN_ROBLOX_SMOKE_SESSION_MILLIS,
                crashObserved = false,
            )
        assertTrue(complete.integrationSmokePassed)
        assertFalse(complete.gameplayValidated)

        val gameplay =
            complete.copy(
                serverSessionJoined = true,
                avatarMovementObserved = true,
                gameplayInteractionObserved = true,
                stableSessionMillis =
                    RobloxRuntimeEvidence
                        .MIN_ROBLOX_GAMEPLAY_SESSION_MILLIS,
            )
        assertTrue(gameplay.integrationSmokePassed)
        assertTrue(gameplay.gameplayValidated)
        assertFalse(
            gameplay.copy(
                gameplayInteractionObserved = false,
            ).gameplayValidated,
        )

        assertFalse(
            complete.copy(
                stableSessionMillis =
                    RobloxRuntimeEvidence
                        .MIN_ROBLOX_SMOKE_SESSION_MILLIS - 1L,
            ).integrationSmokePassed,
        )
        assertFalse(
            complete.copy(
                externalNetworkObserved = false,
            ).integrationSmokePassed,
        )
        assertFalse(
            complete.copy(
                audioOutputObserved = false,
            ).integrationSmokePassed,
        )
        assertFalse(
            complete.copy(
                crashObserved = true,
            ).integrationSmokePassed,
        )
    }
}
