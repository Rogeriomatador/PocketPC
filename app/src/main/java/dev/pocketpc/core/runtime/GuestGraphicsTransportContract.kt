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
    const val externalImagePvi1ProtocolImplemented = true
    const val guestVulkanImportPrimitiveImplemented = true

    /*
     * The pinned Box64 source has not provided verified evidence that a direct
     * libandroid/AHardwareBuffer bridge is available to the x86_64 guest.
     * OPAQUE_FD remains the preferred independent transport candidate.
     */
    const val box64DirectAhardwareBufferBridgeVerified = false

    /*
     * These three gates describe the real session, not the existence of helper
     * functions. They remain false until the authenticated graphics broker is
     * connected to Wine/Box64, a PVI1 resource is imported into the Vulkan
     * device actually used by Wine/DXVK, and ownership/synchronization is
     * exercised end-to-end.
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
            externalImagePvi1ProtocolImplemented &&
            guestReceiveImplemented &&
            guestImportImplemented &&
            synchronizationImplemented
}
