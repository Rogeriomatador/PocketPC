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
    const val externalImagePvi1ProtocolImplemented = true
    const val guestVulkanImportPrimitiveImplemented = true
    const val externalTimelineSemaphorePvs1ProtocolImplemented = true
    const val hostTimelineSemaphoreExporterImplemented = true
    const val guestVulkanTimelineImportPrimitiveImplemented = true
    const val guestGpuQueueSignalPrimitiveImplemented = true
    const val wineVulkanAbiV48DeviceLifecycleSourceIntegrated = true
    const val activeWineDeviceImportSourceIntegrated = true
    const val asynchronousWineDeviceResourceWorkerImplemented = true

    /*
     * The queue signal primitive submits a real timeline semaphore signal to a
     * Wine Vulkan queue, but source presence is not execution evidence and is
     * not yet tied to the queue/presented frame selected by DXVK.
     */
    const val guestGpuQueueSignalExecuted = false
    const val guestGpuQueueSignalCompletionObserved = false
    const val guestGpuQueueSignalPresentOrdered = false

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
     * proven through real GPU work associated with the presentation path.
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
            externalImagePvi1ProtocolImplemented &&
            externalTimelineSemaphorePvs1ProtocolImplemented &&
            guestGpuQueueSignalPrimitiveImplemented &&
            wineVulkanAbiV48DeviceLifecycleSourceIntegrated &&
            activeWineDeviceImportSourceIntegrated &&
            guestReceiveImplemented &&
            guestImportImplemented &&
            synchronizationImplemented
}
