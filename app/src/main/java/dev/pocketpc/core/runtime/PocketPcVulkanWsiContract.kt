package dev.pocketpc.core.runtime

/**
 * Contract for the Wine graphics-driver Vulkan WSI needed by DXVK Present.
 *
 * This is intentionally fail-closed. GDI window_surface flush support does
 * not satisfy Wine's Vulkan graphics-driver ABI, Android-to-Android
 * AHardwareBuffer transport does not prove guest/Wine import, and none of
 * those proofs alone establish a visible Vulkan surface, swapchain or Present
 * path on Android.
 */
object PocketPcVulkanWsiContract {
    /**
     * PocketPC patches the pinned Wine 11 internal ABI from upstream v47 to v49
     * by appending exact VkDevice lifecycle callbacks plus a callback that is
     * invoked with the exact Wine vulkan_queue immediately after host
     * vkQueuePresentKHR. The bump prevents a shorter pre-patch
     * vulkan_driver_funcs layout from being consumed as if those slots existed.
     */
    const val WINE_VULKAN_DRIVER_VERSION =
        49
    const val PINNED_WINE_UPSTREAM_VULKAN_DRIVER_VERSION =
        47
    const val PINNED_WINE_VERSION =
        "11.0"
    const val PINNED_WINE_COMMIT =
        "db11d0fe6a169c457e23d007e20404643d067aa8"

    const val abiEntryPointImplemented =
        true
    const val deviceLifecycleCallbacksImplemented =
        true
    const val presentQueueCallbackImplemented =
        true
    const val presentQueueTimelineSignalSourceIntegrated =
        true

    const val deviceLifecycleCallbacksSoftwareTestExecuted =
        false
    const val presentQueueCallbackSoftwareTestExecuted =
        false
    const val presentQueueTimelineSignalExecuted =
        false
    const val abiEntryPointSoftwareTestExecuted =
        false

    /*
     * External Win32 handle mappings can exist before presentation. They use
     * Wine's normal Linux fd translation model and are deliberately separated
     * from the visible surface/swapchain WSI gates below.
     */
    const val externalHandleExtensionMappingImplemented =
        true
    const val externalHandleExtensionMappingSoftwareTestExecuted =
        false

    /*
     * Diagnostic-only WSI follows Wine's nulldrv model and is enabled only by
     * POCKETPC_VULKAN_HEADLESS_DIAGNOSTIC=1. It can eventually prove that
     * Wine/DXVK can form a host VkSurfaceKHR/swapchain without claiming any
     * Android-visible frame.
     */
    const val headlessDiagnosticSurfaceImplemented =
        true
    const val headlessDiagnosticPresentationSupportImplemented =
        true
    const val headlessDiagnosticExtensionMappingImplemented =
        true
    const val headlessDiagnosticSoftwareTestExecuted =
        false
    const val headlessDiagnosticIntegrationTestExecuted =
        false

    // Production-visible Android WSI remains blocked.
    const val surfaceCreateImplemented =
        false
    const val presentationSupportImplemented =
        false
    const val surfaceExtensionMappingImplemented =
        false
    const val swapchainPresentationImplemented =
        false
    const val swapchainImageCaptureImplemented =
        false
    const val hostVisibleFrameImplemented =
        false

    const val extensionMappingImplemented =
        externalHandleExtensionMappingImplemented &&
            surfaceExtensionMappingImplemented

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
            "p_vulkan_device_created",
            "p_vulkan_device_destroyed",
            "p_vulkan_queue_presented",
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
            "guest-present-queue-timeline-signal",
            "guest-graphics-synchronization",
            "swapchain-image-capture",
            "host-visible-frame",
        )

    fun canEnterWsiIntegrationTest(
        foundation: PocketPcVulkanWsiFoundationStatus,
    ): Boolean =
        implemented &&
            surfaceCreateImplemented &&
            presentationSupportImplemented &&
            surfaceExtensionMappingImplemented &&
            swapchainPresentationImplemented &&
            swapchainImageCaptureImplemented &&
            hostVisibleFrameImplemented &&
            foundation.readyForWsiImplementation &&
            foundation.guestGraphicsTransportReady
}
