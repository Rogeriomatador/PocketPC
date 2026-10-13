package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketPcVulkanWsiFoundationTest {
    private fun nativeHost(
        loaded: Boolean = true,
        hardwareBufferProbe: String =
            "ahardwarebuffer=available;send_handle=ok;recv_handle=ok;" +
                "descriptor_match=yes;cross_process_transport=structurally-ready;" +
                "vulkan_wsi=not-tested",
    ) =
        NativeHostStatus(
            loaded = loaded,
            probe = "native-host=loaded",
            graphicsProbe = "vulkan=available",
            nativeLibraryDir = "/data/app/lib",
            hardwareBufferProbe = hardwareBufferProbe,
        )

    private fun crossProcessEvidence(
        verified: Boolean = true,
        distinct: Boolean = true,
    ) =
        HardwareBufferCrossProcessEvidence(
            senderPid = 100,
            receiverPid = if (distinct) 101 else 100,
            senderResult = "ahb-xproc-send=ok;width=64;height=64",
            receiverResult =
                "ahb-xproc-recv=ok;descriptor_match=yes;pattern_match=yes",
            distinctProcesses = distinct,
            handleTransportVerified = verified,
            vulkanWsiValidated = false,
            error = null,
        )

    @Test
    fun crossProcessTransportCanMakeFoundationReadyWithoutClaimingWsi() {
        val result =
            PocketPcVulkanWsiFoundationProbe.assess(
                nativeHost = nativeHost(),
                crossProcessEvidence = crossProcessEvidence(),
                guestGraphicsTransportReady = true,
            )

        assertTrue(result.readyForWsiImplementation)
        assertTrue(result.crossProcessTransportVerified)
        assertTrue(result.distinctProcessesObserved)
        assertFalse(PocketPcVulkanWsiContract.implemented)
    }

    @Test
    fun sameProcessEvidenceAloneCannotMakeFoundationReady() {
        val result =
            PocketPcVulkanWsiFoundationProbe.assess(
                nativeHost = nativeHost(),
                crossProcessEvidence = null,
            )

        assertFalse(result.readyForWsiImplementation)
        assertTrue(
            result.blockers.contains(
                PocketPcVulkanWsiFoundationProbe
                    .BLOCKER_AHB_CROSS_PROCESS,
            ),
        )
        assertTrue(
            result.blockers.contains(
                PocketPcVulkanWsiFoundationProbe
                    .BLOCKER_DISTINCT_PROCESS,
            ),
        )
    }

    @Test
    fun samePidCannotBePromotedEvenIfEvidenceBooleanWasForged() {
        val forged =
            crossProcessEvidence(
                verified = true,
                distinct = false,
            ).copy(
                distinctProcesses = true,
                handleTransportVerified = true,
            )
        val result =
            PocketPcVulkanWsiFoundationProbe.assess(
                nativeHost = nativeHost(),
                crossProcessEvidence = forged,
            )

        assertFalse(result.readyForWsiImplementation)
        assertFalse(result.distinctProcessesObserved)
        assertTrue(
            result.blockers.contains(
                PocketPcVulkanWsiFoundationProbe
                    .BLOCKER_DISTINCT_PROCESS,
            ),
        )
    }

    @Test
    fun missingNativeProbeCannotBePromotedByCrossProcessEvidence() {
        val result =
            PocketPcVulkanWsiFoundationProbe.assess(
                nativeHost =
                    nativeHost(
                        loaded = false,
                        hardwareBufferProbe =
                            "ahardwarebuffer=not-probed",
                    ),
                crossProcessEvidence = crossProcessEvidence(),
            )

        assertFalse(result.readyForWsiImplementation)
        assertTrue(
            result.blockers.contains(
                PocketPcVulkanWsiFoundationProbe.BLOCKER_NATIVE_HOST,
            ),
        )
        assertTrue(
            result.blockers.contains(
                PocketPcVulkanWsiFoundationProbe
                    .BLOCKER_AHB_SAME_PROCESS,
            ),
        )
    }
}
