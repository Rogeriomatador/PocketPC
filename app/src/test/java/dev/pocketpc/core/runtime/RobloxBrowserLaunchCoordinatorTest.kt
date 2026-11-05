package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RobloxBrowserLaunchCoordinatorTest {
    private fun installation() =
        RobloxPlayerInstallation(
            versionDirectoryName = "version-test",
            hostExecutable = File("/tmp/RobloxPlayerBeta.exe"),
            windowsExecutable =
                "C:\\users\\pocket\\AppData\\Local\\Roblox\\Versions\\" +
                    "version-test\\RobloxPlayerBeta.exe",
            bytes = 123L,
            modifiedAtMillis = 1L,
        )

    private fun readiness(
        state: RobloxLaunchReadinessState,
        ready: Boolean,
        selected: RobloxPlayerInstallation? = installation(),
        blockers: List<String> = emptyList(),
    ) =
        RobloxLaunchReadiness(
            state = state,
            controlledAttemptReady = ready,
            integrationSmokePassed = false,
            gameplayValidated = false,
            selectedInstallation = selected,
            blockers = blockers,
            detail = "test",
        )

    @Test
    fun invalidDeepLinkNeverReachesRuntime() {
        val decision =
            RobloxBrowserLaunchCoordinator.decide(
                rawUri = "https://www.roblox.com/games/1",
                readiness =
                    readiness(
                        RobloxLaunchReadinessState.READY_FOR_CONTROLLED_ATTEMPT,
                        ready = true,
                    ),
            )

        assertEquals(
            RobloxBrowserLaunchDecisionKind.INVALID_DEEP_LINK,
            decision.kind,
        )
        assertFalse(decision.mayBuildControlledAttempt)
    }

    @Test
    fun validDeepLinkStillFailsClosedWhenPlayerIsMissing() {
        val decision =
            RobloxBrowserLaunchCoordinator.decide(
                rawUri = "roblox-player:1+launchmode:play",
                readiness =
                    readiness(
                        RobloxLaunchReadinessState.NOT_INSTALLED,
                        ready = false,
                        selected = null,
                        blockers = listOf("ROBLOX_CLASSIC_PLAYER_NOT_INSTALLED"),
                    ),
            )

        assertEquals(
            RobloxBrowserLaunchDecisionKind.NOT_INSTALLED,
            decision.kind,
        )
        assertFalse(decision.mayBuildControlledAttempt)
        assertTrue(
            decision.blockers.contains(
                RobloxBrowserLaunchCoordinator.BLOCKER_NOT_INSTALLED,
            ),
        )
    }

    @Test
    fun graphicsBlockerIsPreservedForBrowserPlayRequest() {
        val decision =
            RobloxBrowserLaunchCoordinator.decide(
                rawUri = "roblox-player:1+launchmode:play",
                readiness =
                    readiness(
                        RobloxLaunchReadinessState.RUNTIME_BLOCKED,
                        ready = false,
                        blockers =
                            listOf(
                                RobloxLaunchReadinessProbe
                                    .BLOCKER_GUEST_GRAPHICS_TRANSPORT,
                            ),
                    ),
            )

        assertEquals(
            RobloxBrowserLaunchDecisionKind.RUNTIME_BLOCKED,
            decision.kind,
        )
        assertFalse(decision.mayBuildControlledAttempt)
        assertTrue(
            decision.blockers.contains(
                RobloxLaunchReadinessProbe
                    .BLOCKER_GUEST_GRAPHICS_TRANSPORT,
            ),
        )
    }

    @Test
    fun readyDecisionKeepsDeepLinkOpaque() {
        val raw =
            "roblox-player:1+launchmode:play+gameinfo:opaque-value"
        val decision =
            RobloxBrowserLaunchCoordinator.decide(
                rawUri = raw,
                readiness =
                    readiness(
                        RobloxLaunchReadinessState.READY_FOR_CONTROLLED_ATTEMPT,
                        ready = true,
                    ),
            )

        assertEquals(
            RobloxBrowserLaunchDecisionKind.READY_FOR_CONTROLLED_ATTEMPT,
            decision.kind,
        )
        assertTrue(decision.mayBuildControlledAttempt)
        assertEquals(raw, decision.deepLink?.raw)
        assertTrue(decision.blockers.isEmpty())
    }
}
