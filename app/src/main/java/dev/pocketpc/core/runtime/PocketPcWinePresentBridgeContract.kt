package dev.pocketpc.core.runtime

/**
 * Source/runtime-state contract for the PocketPC Wine Vulkan Present bridge.
 *
 * Source integration, execution, visible presentation and Roblox execution are
 * deliberately independent gates. Never promote a later gate from an earlier
 * one without matching evidence.
 */
object PocketPcWinePresentBridgeContract {
    const val privateWineVulkanAbi = 51

    const val exactPresentedImageSourceIntegrated = true
    const val externalOwnershipSourceIntegrated = true
    const val presentSemaphoreChainSourceIntegrated = true

    const val pixelCopyImplemented = true
    const val pixelCopyExecuted = false
    const val formatConversionImplemented = false
    const val scalingImplemented = false
    const val androidVisiblePresentImplemented = false
    const val runtimeExecuted = false
    const val robloxExecuted = false

    const val BLOCKER_PIXEL_COPY =
        "ROBLOX_VULKAN_PRESENT_PIXEL_COPY_NOT_IMPLEMENTED"
    const val BLOCKER_PIXEL_COPY_EXECUTION =
        "ROBLOX_VULKAN_PRESENT_PIXEL_COPY_NOT_EXECUTED"
    const val BLOCKER_ANDROID_VISIBLE_PRESENT =
        "ROBLOX_VULKAN_ANDROID_VISIBLE_PRESENT_NOT_IMPLEMENTED"
    const val BLOCKER_PRESENT_RUNTIME =
        "ROBLOX_VULKAN_PRESENT_BRIDGE_NOT_RUNTIME_TESTED"
    const val BLOCKER_ROBLOX_EXECUTION =
        "ROBLOX_WINDOWS_RUNTIME_NOT_EXECUTED"

    fun blockers(): List<String> =
        buildList {
            if (!pixelCopyImplemented) {
                add(BLOCKER_PIXEL_COPY)
            }
            if (!pixelCopyExecuted) {
                add(BLOCKER_PIXEL_COPY_EXECUTION)
            }
            if (!androidVisiblePresentImplemented) {
                add(BLOCKER_ANDROID_VISIBLE_PRESENT)
            }
            if (!runtimeExecuted) {
                add(BLOCKER_PRESENT_RUNTIME)
            }
            if (!robloxExecuted) {
                add(BLOCKER_ROBLOX_EXECUTION)
            }
        }

    fun sourceIntegratedForPixelCopyAttempt(): Boolean =
        privateWineVulkanAbi == 51 &&
            exactPresentedImageSourceIntegrated &&
            externalOwnershipSourceIntegrated &&
            presentSemaphoreChainSourceIntegrated &&
            pixelCopyImplemented

    fun readyForRobloxGraphics(): Boolean =
        sourceIntegratedForPixelCopyAttempt() &&
            pixelCopyExecuted &&
            androidVisiblePresentImplemented &&
            runtimeExecuted &&
            robloxExecuted
}
