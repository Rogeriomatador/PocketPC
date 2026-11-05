package dev.pocketpc.core.runtime

/**
 * Contract for the Wine graphics-driver Vulkan path needed by DXVK Present.
 *
 * This object deliberately separates SOURCE IMPLEMENTATION from EXECUTED
 * evidence. PocketPC now has source/preparer plumbing for exact presented-image
 * copy, continuous v52 ownership, Android host readback, desktop-model delivery
 * and Compose draw submission. None of those source facts prove that the chain
 * executed on Android or that a frame was physically visible on a device.
 */
object PocketPcVulkanWsiContract {
    /**
     * Private Wine Vulkan ABI progression used by PocketPC.
     *
     * 47 = pinned upstream Wine 11 ABI
     * 49 = base PocketPC device lifecycle + Present queue callbacks
     * 50 = generic/current fail-closed WSI contract with exact image identity
     * 51 = exact image -> PVI1 copy overlay
     * 52 = continuous odd/even PVS1 ownership overlay
     *
     * The generic WSI gate intentionally remains v50. v51/v52 are separate
     * prepared overlays selected by their own verified runtime policy; they do
     * not promote the generic Android-visible WSI gate by merely existing.
     */
    const val WINE_VULKAN_DRIVER_VERSION =
        50
    const val V52_CONTINUOUS_PRESENT_VULKAN_DRIVER_VERSION =
        52
    const val V51_EXACT_PRESENT_COPY_VULKAN_DRIVER_VERSION =
        51
    const val V50_EXACT_PRESENTED_IMAGE_VULKAN_DRIVER_VERSION =
        50
    const val BASE_OVERLAY_VULKAN_DRIVER_VERSION =
        49
    const val PINNED_WINE_UPSTREAM_VULKAN_DRIVER_VERSION =
        47
    const val PINNED_WINE_VERSION =
        "11.0"
    const val PINNED_WINE_COMMIT =
        "db11d0fe6a169c457e23d007e20404643d067aa8"

    /*
     * Executed host-only evidence. This is intentionally revision-scoped so a
     * later source change cannot silently inherit a generic PASS claim.
     */
    const val HEADLESS_HOST_EVIDENCE_REVISION =
        "90a593f087603ffa31a3380c63ab10aa14a5938f"
    const val HEADLESS_HOST_EVIDENCE_RUN_ID =
        34890837526L
    const val HEADLESS_HOST_EVIDENCE_PLATFORM =
        "linux-x86_64"

    const val abiEntryPointImplemented =
        true
    const val deviceLifecycleCallbacksImplemented =
        true
    const val presentQueueCallbackImplemented =
        true
    const val presentQueueTimelineSignalSourceIntegrated =
        true
    const val exactPresentedImageIdentitySourceIntegrated =
        true

    const val deviceLifecycleCallbacksSoftwareTestExecuted =
        false
    const val presentQueueCallbackSoftwareTestExecuted =
        false
    const val presentQueueTimelineSignalExecuted =
        false
    const val exactPresentedImageIdentityExecuted =
        false
    const val abiEntryPointSoftwareTestExecuted =
        false

    /*
     * Source pipeline after exact Present identity. These flags mean the source
     * and preparers exist; they do not mean Android, DXVK or physical execution.
     */
    const val swapchainImageCaptureSourceIntegrated =
        true
    const val continuousPresentOwnershipSourceIntegrated =
        true
    const val androidHostReadbackSourceIntegrated =
        true
    const val desktopModelFrameDeliverySourceIntegrated =
        true
    const val composeFrameDrawSourceIntegrated =
        true

    const val androidHostReadbackIntegrationTestExecuted =
        false
    const val desktopModelFrameDeliveryIntegrationTestExecuted =
        false
    const val composeFrameDrawIntegrationTestExecuted =
        false
    const val hostVisibleFramePhysicalTestExecuted =
        false

    /*
     * External Win32 handle mappings can exist before presentation. They use
     * Wine's normal Linux fd translation model and remain separated from the
     * production-visible surface/swapchain gates below.
     */
    const val externalHandleExtensionMappingImplemented =
        true
    const val externalHandleExtensionMappingSoftwareTestExecuted =
        false

    /*
     * Diagnostic-only WSI follows Wine's nulldrv model and is enabled only by
     * POCKETPC_VULKAN_HEADLESS_DIAGNOSTIC=1.
     *
     * The revision-scoped host CI above proved the private ABI initialized and
     * a Win32 surface request mapped to a host headless VkSurfaceKHR. It did NOT
     * prove visible presentation, swapchain pixels, DXVK, Android or Roblox.
     */
    const val headlessDiagnosticSurfaceImplemented =
        true
    const val headlessDiagnosticPresentationSupportImplemented =
        true
    const val headlessDiagnosticExtensionMappingImplemented =
        true
    const val headlessDiagnosticSoftwareTestExecuted =
        true
    const val headlessDiagnosticIntegrationTestExecuted =
        true

    // Production-visible Android WSI remains blocked / not executed.
    const val surfaceCreateImplemented =
        false
    const val presentationSupportImplemented =
        false
    const val surfaceExtensionMappingImplemented =
        false
    const val swapchainPresentationImplemented =
        false

    /*
     * Prepared exact-image copy/readback source exists, but the production
     * Android runtime path remains fail-closed until authenticated
     * PRoot/Box64/Wine/DXVK execution proves it. Compose source capable of
     * drawing a returned frame is not physical-visible-frame evidence.
     */
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
            "p_vulkan_image_presented",
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
            "exact-presented-swapchain-image-identity",
            "guest-graphics-synchronization",
            "swapchain-image-capture",
            "android-host-readback",
            "desktop-model-frame-delivery",
            "compose-frame-draw-submission",
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
