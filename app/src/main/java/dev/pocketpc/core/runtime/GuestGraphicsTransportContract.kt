package dev.pocketpc.core.runtime

/**
 * Fail-closed contract for graphics-handle transport from the Android host
 * into the actual guest/Wine execution side.
 *
 * Foundations may be implemented before they are executed in a real runtime
 * session. These flags deliberately distinguish reusable/integrated source code
 * from observed Box64/Wine/DXVK receive/import/synchronization evidence.
 */
object GuestGraphicsTransportContract {
    const val PROTOCOL_VERSION = 1

    const val descriptorProtocolImplemented = true
    const val ownershipProtocolImplemented = true
    const val hostAhardwareBufferBrokerImplemented = true
    const val hostOpaqueFdImageBrokerImplemented = true
    const val hostOpaqueFdInitialExternalReleaseSourceIntegrated = true
    const val guestExternalImageAcquireReleasePrimitiveImplemented = true
    const val externalImageBoundaryLayoutGeneral = true
    const val hostDmaBufImageBrokerImplemented = false
    const val externalResourceCapabilityProbeImplemented = true
    const val canonicalAhardwareBufferImportProbeImplemented = true
    const val ancillaryFdTransportPrimitiveImplemented = true
    const val handleBindingImplemented = true
    const val guestReceivePrimitiveImplemented = true
    const val authenticatedSessionHandshakePrimitiveImplemented = true
    const val androidSeqpacketSessionHostImplemented = true
    const val nativeAuthenticationDeadlineImplemented = true
    const val guestPgh1SessionClientImplemented = true
    const val graphicsSessionOrchestratorImplemented = true
    const val prootGraphicsEnvironmentInjectionImplemented = true
    const val runtimeDisplayGraphicsEnvironmentInjectionImplemented = true
    const val canonicalPgtResourceOfferImplemented = true
    const val authenticatedHostPgtPvi1Pvs1OfferImplemented = true
    const val authenticatedHostPvi1Pvs1OfferImplemented = true
    const val guestImportAcknowledgementProtocolImplemented = true
    const val guestImportAcknowledgementHostValidationImplemented = true
    const val guestImportOwnershipPromotionImplemented = true
    const val guestGpuQueueSignalAcknowledgementStageImplemented = true
    const val guestGpuQueueSignalAcknowledgementHostValidationImplemented = true
    const val runtimeDisplayGpuQueueSignalObservationImplemented = true
    const val externalImagePvi1ProtocolImplemented = true
    const val guestVulkanImportPrimitiveImplemented = true
    const val externalTimelineSemaphorePvs1ProtocolImplemented = true
    const val hostTimelineSemaphoreExporterImplemented = true
    const val guestVulkanTimelineImportPrimitiveImplemented = true
    const val guestGpuQueueSignalPrimitiveImplemented = true

    /* Reusable diagnostic helper; no longer used to classify Present ordering. */
    const val guestGpuFirstQueueSignalProbeImplemented = true

    /* Base PocketPC overlay remains v49 before the staged v50 image patch. */
    const val wineVulkanAbiV49DeviceLifecycleSourceIntegrated = true
    const val wineVulkanAbiV49PresentQueueCallbackSourceIntegrated = true
    const val wineVulkanAbiV50ExactPresentedImageSourceIntegrated = true
    const val presentQueueTimelineSignalSourceIntegrated = true
    const val activeWineDeviceImportSourceIntegrated = true
    const val asynchronousWineDeviceResourceWorkerImplemented = true

    /*
     * The Android OPAQUE_FD broker now records a real initial Vulkan release
     * from its graphics queue to VK_QUEUE_FAMILY_EXTERNAL in GENERAL before it
     * will send PVI1. The Wine helper can perform the matching acquire/release
     * on the exact Wine queue. These are source foundations only until a real
     * Android/Box64/Wine run observes the transitions.
     */
    const val hostOpaqueFdInitialExternalReleaseExecuted = false
    const val guestExternalImageAcquireExecuted = false
    const val guestExternalImageReleaseExecuted = false

    /*
     * The v49 Present callback receives the exact struct vulkan_queue used by
     * win32u_vkQueuePresentKHR and submits the PVS1 signal on that same queue.
     * The staged v50 patch additionally resolves pImageIndices to the exact
     * host swapchain VkImage. Source presence is not execution evidence.
     */
    const val guestGpuQueueSignalExecuted = false
    const val guestGpuQueueSignalCompletionObserved = false
    const val guestGpuQueueSignalPresentOrdered = false
    const val exactPresentedImageIdentityExecuted = false
    const val guestGpuQueueSignalHostVisibleFrame = false

    /*
     * The pinned Box64 source has not provided verified evidence that a direct
     * libandroid/AHardwareBuffer bridge is available to the x86_64 guest.
     * OPAQUE_FD remains the preferred independent transport candidate.
     */
    const val box64DirectAhardwareBufferBridgeVerified = false

    /*
     * These three gates describe OBSERVED integration in the real guest, not
     * the existence of source code, host sendmsg(), PGA1 source handling, or a
     * queue-submit helper. They remain false until a launched Box64/Wine guest
     * actually receives/imports the canonical resources and synchronization is
     * proven through executed GPU work. A visible frame additionally requires
     * swapchain image capture/copy and Android presentation.
     */
    const val guestReceiveImplemented = false
    const val guestImportImplemented = false
    const val synchronizationImplemented = false

    const val softwareTestExecuted = false
    const val integrationTestExecuted = false
    const val physicalTestExecuted = false

    const val blocker =
        "VULKAN_WSI_GUEST_GRAPHICS_TRANSPORT_NOT_IMPLEMENTED"

    fun readyForWsiImplementation(): Boolean =
        descriptorProtocolImplemented &&
            ownershipProtocolImplemented &&
            authenticatedSessionHandshakePrimitiveImplemented &&
            androidSeqpacketSessionHostImplemented &&
            nativeAuthenticationDeadlineImplemented &&
            guestPgh1SessionClientImplemented &&
            graphicsSessionOrchestratorImplemented &&
            prootGraphicsEnvironmentInjectionImplemented &&
            runtimeDisplayGraphicsEnvironmentInjectionImplemented &&
            canonicalPgtResourceOfferImplemented &&
            authenticatedHostPgtPvi1Pvs1OfferImplemented &&
            guestImportAcknowledgementProtocolImplemented &&
            guestImportAcknowledgementHostValidationImplemented &&
            guestImportOwnershipPromotionImplemented &&
            guestGpuQueueSignalAcknowledgementHostValidationImplemented &&
            runtimeDisplayGpuQueueSignalObservationImplemented &&
            externalImagePvi1ProtocolImplemented &&
            hostOpaqueFdInitialExternalReleaseSourceIntegrated &&
            guestExternalImageAcquireReleasePrimitiveImplemented &&
            externalTimelineSemaphorePvs1ProtocolImplemented &&
            guestGpuQueueSignalPrimitiveImplemented &&
            wineVulkanAbiV49DeviceLifecycleSourceIntegrated &&
            wineVulkanAbiV49PresentQueueCallbackSourceIntegrated &&
            wineVulkanAbiV50ExactPresentedImageSourceIntegrated &&
            presentQueueTimelineSignalSourceIntegrated &&
            activeWineDeviceImportSourceIntegrated &&
            guestReceiveImplemented &&
            guestImportImplemented &&
            synchronizationImplemented
}
