package dev.pocketpc.core.runtime

/**
 * Fail-closed contract for graphics-handle transport from the Android host
 * into the actual guest/Wine execution side.
 *
 * Foundations may be implemented before they are wired into a real runtime
 * session. These flags deliberately distinguish reusable primitives from an
 * authenticated, executed Box64/Wine/DXVK path.
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
     * These three gates describe the real session, not the existence of helper
     * functions. They remain false until the authenticated graphics broker is
     * injected into a launched Wine/Box64 process, a PVI1 resource plus PVS1
     * semaphore are imported into the Vulkan device actually used by Wine/DXVK,
     * and monotonic GPU signal/wait ownership is exercised end-to-end.
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
            externalImagePvi1ProtocolImplemented &&
            externalTimelineSemaphorePvs1ProtocolImplemented &&
            guestReceiveImplemented &&
            guestImportImplemented &&
            synchronizationImplemented
}
