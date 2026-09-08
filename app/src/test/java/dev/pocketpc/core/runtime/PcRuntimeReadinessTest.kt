package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcRuntimeReadinessTest {
    @Test
    fun windowsExecutionRemainsFailClosedWithoutTranslationAndWin32() {
        val result =
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
            )

        assertEquals(3, result.readyCount)
        assertFalse(result.executableReady)
        assertTrue(
            result.stages.any {
                it.id == "x86-64-translation" &&
                    it.state ==
                        PcRuntimeStageState
                            .BLOCKED
            }
        )
        assertTrue(
            result.stages.any {
                it.id == "roblox-compatibility" &&
                    it.state ==
                        PcRuntimeStageState.UNKNOWN
            }
        )
    }

    @Test
    fun missingHostSubstrateAndRootfsStayBlocked() {
        val result =
            PcRuntimeReadinessProbe.assess(
                nativeHost =
                    NativeHostStatus(
                        loaded = false,
                        probe = "failed",
                        graphicsProbe = "not-probed",
                        nativeLibraryDir = "",
                    ),
                substrate =
                    ExecutionSubstrateStatus(
                        nativeLibraryDir = "",
                        packagedHostReady = false,
                        prootReady = false,
                        components = emptyList(),
                        state = "BLOCKED",
                    ),
                installedRuntimeCount = 0,
                preparedRuntimeCount = 0,
            )

        assertEquals(0, result.readyCount)
        assertFalse(result.executableReady)
        assertTrue(
            result.stages.count {
                it.state ==
                    PcRuntimeStageState.BLOCKED
            } >= 7,
        )
    }
    @Test
    fun installedButUnpreparedRootfsStaysBlocked() {
        val result =
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
                preparedRuntimeCount = 0,
            )

        val rootfs = result.stages.single { it.id == "rootfs" }
        assertEquals(PcRuntimeStageState.BLOCKED, rootfs.state)
        assertTrue(rootfs.detail.contains("nenhum está preparado"))
        assertFalse(result.executableReady)
    }
    @Test
    fun ioFoundationIsReportedWithoutPromotingWindowsIo() {
        val result =
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
                        prootReady = false,
                        components = emptyList(),
                        state = "BLOCKED",
                    ),
                installedRuntimeCount = 0,
                preparedRuntimeCount = 0,
                ioHost =
                    RuntimeIoHostCapabilities(
                        networkInternetCapable = true,
                        networkValidated = true,
                        audioOutputCount = 2,
                        keyboardCount = 1,
                        mouseCount = 1,
                        gamepadCount = 0,
                    ),
            )

        val io = result.stages.single { it.id == "io-integration" }
        assertEquals(PcRuntimeStageState.NOT_IMPLEMENTED, io.state)
        assertTrue(io.detail.contains("áudio=2"))
        assertTrue(io.detail.contains("internet=validada"))
        assertFalse(result.executableReady)
    }

    @Test
    fun smokeEvidencePromotesTranslationAndWineOnly() {
        val result =
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
                        windowsProcessSmokePassed = true,
                    ),
                windowsStateReady = true,
            )

        assertEquals(
            PcRuntimeStageState.READY,
            result.stages.single {
                it.id == "x86-64-translation"
            }.state,
        )
        assertEquals(
            PcRuntimeStageState.READY,
            result.stages.single {
                it.id == "win32-compat"
            }.state,
        )
        assertEquals(
            PcRuntimeStageState.READY,
            result.stages.single {
                it.id == "windows-state"
            }.state,
        )
        assertEquals(
            PcRuntimeStageState.BLOCKED,
            result.stages.single {
                it.id == "graphics-bridge"
            }.state,
        )
        assertFalse(result.executableReady)
    }

    @Test
    fun wineWithoutProcessIpcKeepsWindowsStateBlocked() {
        val result =
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
                        windowsProcessSmokePassed = false,
                    ),
                windowsStateReady = true,
            )

        val windows =
            result.stages.single {
                it.id == "windows-state"
            }
        assertEquals(
            PcRuntimeStageState.BLOCKED,
            windows.state,
        )
        assertTrue(
            windows.detail.contains(
                "CreateProcess/IPC",
            ),
        )
    }

    @Test
    fun graphicsDeviceWithoutPresentStaysBlocked() {
        val result =
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
                        d3d11SmokePassed = true,
                        graphicsPresentationSmokePassed = false,
                        windowsProcessSmokePassed = true,
                    ),
                windowsStateReady = true,
            )

        val graphics =
            result.stages.single {
                it.id == "graphics-bridge"
            }
        assertEquals(
            PcRuntimeStageState.BLOCKED,
            graphics.state,
        )
        assertTrue(
            graphics.detail.contains(
                "swapchain/Present",
            ),
        )
    }

    @Test
    fun graphicsDeviceAndPresentCanPromoteGraphicsStage() {
        val result =
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
                        d3d11SmokePassed = true,
                        graphicsPresentationSmokePassed = true,
                        windowsProcessSmokePassed = true,
                    ),
                windowsStateReady = true,
            )

        assertEquals(
            PcRuntimeStageState.READY,
            result.stages.single {
                it.id == "graphics-bridge"
            }.state,
        )
        assertFalse(result.executableReady)
    }

}
