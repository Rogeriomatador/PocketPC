package dev.pocketpc.core.runtime

/**
 * Contract for the Wine graphics-driver Vulkan WSI needed by DXVK Present.
 *
 * This is intentionally fail-closed. GDI window_surface flush support does
 * not satisfy Wine's Vulkan graphics-driver ABI.
 */
object PocketPcVulkanWsiContract {
    const val WINE_VULKAN_DRIVER_VERSION =
        47
    const val PINNED_WINE_VERSION =
        "11.0"
    const val PINNED_WINE_COMMIT =
        "db11d0fe6a169c457e23d007e20404643d067aa8"

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
}
