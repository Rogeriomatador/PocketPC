package dev.pocketpc.core.runtime

/**
 * Source-state contract for the PocketPC Wine Vulkan Present bridge.
 *
 * Keep this deliberately stricter than capability advertisement. A source
 * integration flag only says the corresponding path exists in the current
 * branch; it never promotes runtime, physical, visible-frame or Roblox proof.
 */
object PocketPcWinePresentBridgeContract {
    const val privateWineVulkanAbi = 50

    const val exactPresentedImageSourceIntegrated = true
    const val externalOwnershipSourceIntegrated = true

    const val pixelCopyImplemented = false
    const val androidVisiblePresentImplemented = false
    const val runtimeExecuted = false
    const val robloxExecuted = false

    const val BLOCKER_PIXEL_COPY =
        "ROBLOX_VULKAN_PRESENT_PIXEL_COPY_NOT_IMPLEMENTED"
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
        privateWineVulkanAbi == 50 &&
            exactPresentedImageSourceIntegrated &&
            externalOwnershipSourceIntegrated

    fun readyForRobloxGraphics(): Boolean =
        sourceIntegratedForPixelCopyAttempt() &&
            pixelCopyImplemented &&
            androidVisiblePresentImplemented &&
            runtimeExecuted &&
            robloxExecuted
}
