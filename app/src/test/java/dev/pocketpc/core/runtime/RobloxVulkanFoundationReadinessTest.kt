package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RobloxVulkanFoundationReadinessTest {
    private fun installation(): RobloxInstallationDiscovery {
        val selected =
            RobloxPlayerInstallation(
                versionDirectoryName = "version-test",
                hostExecutable = File("/tmp/RobloxPlayerBeta.exe"),
                windowsExecutable =
                    "C:\\users\\pocket\\AppData\\Local\\Roblox\\Versions\\" +
                        "version-test\\RobloxPlayerBeta.exe",
                bytes = 123L,
                modifiedAtMillis = 1L,
            )
        return RobloxInstallationDiscovery(
            versionsRoot = File("/tmp/Versions"),
            installations = listOf(selected),
            selected = selected,
            blockers = emptyList(),
        )
    }

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

    private fun runtime() =
        PcRuntimeReadiness(
            target = "test",
            stages = emptyList(),
            executableReady = false,
            controlledAttemptReady = true,
        )

    @Test
    fun missingTransportFoundationAddsDedicatedRobloxBlocker() {
        val result =
            RobloxLaunchReadinessProbe.assess(
                runtimeReadiness = runtime(),
                probeEvidence = allRuntimeEvidence(),
                installation = installation(),
                wsiFoundation = null,
            )

        assertFalse(result.controlledAttemptReady)
        assertTrue(
            result.blockers.contains(
                RobloxLaunchReadinessProbe
                    .BLOCKER_VULKAN_TRANSPORT_FOUNDATION,
            ),
        )
        assertTrue(
            result.blockers.contains(
                PocketPcVulkanWsiContract.blocker,
            ),
        )
    }

    @Test
    fun provenTransportFoundationDoesNotPretendWsiExists() {
        val foundation =
            PocketPcVulkanWsiFoundationStatus(
                nativeHostLoaded = true,
                sameProcessTransportStructurallyReady = true,
                crossProcessTransportVerified = true,
                distinctProcessesObserved = true,
                readyForWsiImplementation = true,
                blockers = emptyList(),
            )
        val result =
            RobloxLaunchReadinessProbe.assess(
                runtimeReadiness = runtime(),
                probeEvidence = allRuntimeEvidence(),
                installation = installation(),
                wsiFoundation = foundation,
            )

        assertFalse(PocketPcVulkanWsiContract.implemented)
        assertFalse(result.controlledAttemptReady)
        assertFalse(
            result.blockers.contains(
                RobloxLaunchReadinessProbe
                    .BLOCKER_VULKAN_TRANSPORT_FOUNDATION,
            ),
        )
        assertTrue(
            result.blockers.contains(
                PocketPcVulkanWsiContract.blocker,
            ),
        )
    }
}
