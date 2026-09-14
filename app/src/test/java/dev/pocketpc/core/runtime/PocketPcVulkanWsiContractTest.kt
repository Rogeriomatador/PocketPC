package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketPcVulkanWsiContractTest {
    @Test
    fun preparedV52SourcePipelineIsRecordedWithoutPromotingAndroidExecution() {
        assertEquals(52, PocketPcVulkanWsiContract.WINE_VULKAN_DRIVER_VERSION)
        assertTrue(PocketPcVulkanWsiContract.swapchainImageCaptureSourceIntegrated)
        assertTrue(PocketPcVulkanWsiContract.continuousPresentOwnershipSourceIntegrated)
        assertTrue(PocketPcVulkanWsiContract.androidHostReadbackSourceIntegrated)
        assertTrue(PocketPcVulkanWsiContract.desktopModelFrameDeliverySourceIntegrated)
        assertTrue(PocketPcVulkanWsiContract.composeFrameDrawSourceIntegrated)

        assertFalse(PocketPcVulkanWsiContract.androidHostReadbackIntegrationTestExecuted)
        assertFalse(PocketPcVulkanWsiContract.desktopModelFrameDeliveryIntegrationTestExecuted)
        assertFalse(PocketPcVulkanWsiContract.composeFrameDrawIntegrationTestExecuted)
        assertFalse(PocketPcVulkanWsiContract.hostVisibleFramePhysicalTestExecuted)
        assertFalse(PocketPcVulkanWsiContract.swapchainImageCaptureImplemented)
        assertFalse(PocketPcVulkanWsiContract.hostVisibleFrameImplemented)
        assertFalse(PocketPcVulkanWsiContract.implemented)
        assertFalse(PocketPcVulkanWsiContract.physicalTestExecuted)
    }

    @Test
    fun hostHeadlessEvidenceRemainsRevisionAndPlatformScoped() {
        assertTrue(PocketPcVulkanWsiContract.headlessDiagnosticIntegrationTestExecuted)
        assertEquals(
            "90a593f087603ffa31a3380c63ab10aa14a5938f",
            PocketPcVulkanWsiContract.HEADLESS_HOST_EVIDENCE_REVISION,
        )
        assertEquals(34890837526L, PocketPcVulkanWsiContract.HEADLESS_HOST_EVIDENCE_RUN_ID)
        assertEquals(
            "linux-x86_64",
            PocketPcVulkanWsiContract.HEADLESS_HOST_EVIDENCE_PLATFORM,
        )

        assertFalse(PocketPcVulkanWsiContract.surfaceCreateImplemented)
        assertFalse(PocketPcVulkanWsiContract.presentationSupportImplemented)
        assertFalse(PocketPcVulkanWsiContract.surfaceExtensionMappingImplemented)
        assertFalse(PocketPcVulkanWsiContract.swapchainPresentationImplemented)
    }
}
