package dev.pocketpc.core.runtime

/**
 * Contract for the Wine graphics-driver Vulkan WSI needed by DXVK Present.
 *
 * This is intentionally fail-closed. GDI window_surface flush support does
 * not satisfy Wine's Vulkan graphics-driver ABI, Android-to-Android
 * AHardwareBuffer transport does not prove guest/Wine import, and none of
 * those proofs alone establish a Vulkan surface, swapchain or Present path.
 */
object PocketPcVulkanWsiContract {
    const val WINE_VULKAN_DRIVER_VERSION =
        47
    const val PINNED_WINE_VERSION =
        "11.0"
    const val PINNED_WINE_COMMIT =
        "db11d0fe6a169c457e23d007e20404643d067aa8"

    // The v47 pVulkanInit ABI is represented by the driver, but all WSI
    // behavior below intentionally remains blocked until the real transport
    // and presentation path exists.
    const val abiEntryPointImplemented =
        true
    const val abiEntryPointSoftwareTestExecuted =
        false
    const val surfaceCreateImplemented =
        false
    const val presentationSupportImplemented =
        false
    const val extensionMappingImplemented =
        false
    const val swapchainPresentationImplemented =
        false

    const val implemented =
        false
    const val softwareTestExecuted =
        false
    const val physicalTestExecuted =
        false

    const val blocker =
        "VULKAN_WSI_NOT_IMPLEMENTED"

    val requiredDriverCallbacks:
        Set<String> =
        setOf(
            "p_vulkan_surface_create",
            "p_get_physical_device_presentation_support",
            "p_map_instance_extensions",
            "p_map_device_extensions",
        )

    val requiredTransportProofs:
        Set<String> =
        setOf(
            "native-host-loaded",
            "ahardwarebuffer-same-process-structural-roundtrip",
            "ahardwarebuffer-cross-process-handle-roundtrip",
            "distinct-sender-receiver-processes",
            "guest-graphics-handle-receive",
            "guest-graphics-resource-import",
            "guest-graphics-synchronization",
        )

    fun canEnterWsiIntegrationTest(
        foundation: PocketPcVulkanWsiFoundationStatus,
    ): Boolean =
        implemented &&
            surfaceCreateImplemented &&
            presentationSupportImplemented &&
            extensionMappingImplemented &&
            swapchainPresentationImplemented &&
            foundation.readyForWsiImplementation &&
            foundation.guestGraphicsTransportReady
}
