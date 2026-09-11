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
    const val externalImagePvi1ProtocolImplemented = true
    const val guestVulkanImportPrimitiveImplemented = true
    const val externalTimelineSemaphorePvs1ProtocolImplemented = true
    const val hostTimelineSemaphoreExporterImplemented = true
    const val guestVulkanTimelineImportPrimitiveImplemented = true
    const val wineVulkanAbiV48DeviceLifecycleSourceIntegrated = true
    const val activeWineDeviceImportSourceIntegrated = true
    const val asynchronousWineDeviceResourceWorkerImplemented = true

    /*
     * The pinned Box64 source has not provided verified evidence that a direct
     * libandroid/AHardwareBuffer bridge is available to the x86_64 guest.
     * OPAQUE_FD remains the preferred independent transport candidate.
     */
    const val box64DirectAhardwareBufferBridgeVerified = false

    /*
     * These three gates describe OBSERVED integration in the real guest, not
     * the existence of source code or the host successfully calling sendmsg().
     * They remain false until a launched Box64/Wine guest actually receives the
     * canonical PGT/PVI1/PVS1 sequence, imports it into the active DXVK device,
     * and exercises ownership through real GPU queue work end-to-end.
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
            externalImagePvi1ProtocolImplemented &&
            externalTimelineSemaphorePvs1ProtocolImplemented &&
            wineVulkanAbiV48DeviceLifecycleSourceIntegrated &&
            activeWineDeviceImportSourceIntegrated &&
            guestReceiveImplemented &&
            guestImportImplemented &&
            synchronizationImplemented
}
