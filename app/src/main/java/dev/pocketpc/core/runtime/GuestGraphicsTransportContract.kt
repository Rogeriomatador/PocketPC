package dev.pocketpc.core.runtime

/**
 * Fail-closed contract for graphics-handle transport from the Android host
 * into the actual guest/Wine execution side.
 *
 * Foundations may be implemented before they are executed in a real runtime
 * session. These flags deliberately distinguish reusable/integrated host code
 * from an observed Box64/Wine/DXVK receive/import/synchronization path.
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
    const val graphicsSessionOrchestratorImplemented = true
    const val prootGraphicsEnvironmentInjectionImplemented = true
    const val authenticatedHostPvi1Pvs1OfferImplemented = true
    const val externalImagePvi1ProtocolImplemented = true
    const val guestVulkanImportPrimitiveImplemented = true
    const val externalTimelineSemaphorePvs1ProtocolImplemented = true
    const val hostTimelineSemaphoreExporterImplemented = true
    const val guestVulkanTimelineImportPrimitiveImplemented = true

    /*
     * The pinned Box64 source has not provided verified evidence that a direct
     * libandroid/AHardwareBuffer bridge is available to the x86_64 guest.
     * OPAQUE_FD remains the preferred independent transport candidate.
     */
    const val box64DirectAhardwareBufferBridgeVerified = false

    /*
     * These three gates describe observed integration in the real guest, not
     * the existence of helper functions or the host successfully calling
     * sendmsg(). They stay false until a launched Box64/Wine guest actually
     * receives PVI1/PVS1, imports both into the Vulkan objects used by DXVK and
     * exercises the ownership timeline on GPU work end-to-end.
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
            graphicsSessionOrchestratorImplemented &&
            prootGraphicsEnvironmentInjectionImplemented &&
            authenticatedHostPvi1Pvs1OfferImplemented &&
            externalImagePvi1ProtocolImplemented &&
            externalTimelineSemaphorePvs1ProtocolImplemented &&
            guestReceiveImplemented &&
            guestImportImplemented &&
            synchronizationImplemented
}
