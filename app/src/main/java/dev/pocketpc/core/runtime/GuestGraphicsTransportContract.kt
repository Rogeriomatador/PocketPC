package dev.pocketpc.core.runtime

/**
 * Fail-closed contract for graphics-handle transport from the Android host
 * into the actual guest/Wine execution side.
 *
 * Android-to-Android AHardwareBuffer transfer is useful host evidence, but it
 * does not prove that Box64/Wine can receive, identify, import or synchronize
 * the same graphics resource. This gate exists so host transport evidence can
 * never be promoted into guest graphics readiness by accident.
 */
object GuestGraphicsTransportContract {
    const val PROTOCOL_VERSION = 1

    const val descriptorProtocolImplemented =
        true
    const val guestReceiveImplemented =
        false
    const val guestImportImplemented =
        false
    const val synchronizationImplemented =
        false

    const val softwareTestExecuted =
        false
    const val integrationTestExecuted =
        false
    const val physicalTestExecuted =
        false

    const val blocker =
        "VULKAN_WSI_GUEST_GRAPHICS_TRANSPORT_NOT_IMPLEMENTED"

    fun readyForWsiImplementation(): Boolean =
        descriptorProtocolImplemented &&
            guestReceiveImplemented &&
            guestImportImplemented &&
            synchronizationImplemented
}
