package dev.pocketpc.core.runtime

/**
 * Explicit, fail-closed selection for the Wine Vulkan Present protocol.
 *
 * v51 remains the default. v52 can only be selected when verified artifact
 * metadata is bound to the exact runtime identity being launched and declares
 * both private Wine Vulkan ABI 52 and the continuous-present capability.
 * Merely having v52 host/guest source in the APK/runtime tree never selects it.
 */
enum class RuntimeGraphicsPresentMode {
    V51_ONE_SHOT,
    V52_CONTINUOUS_EXPERIMENTAL,
}

data class RuntimeGraphicsGuestDeclaration(
    val runtimeIdentity: String,
    val wineVulkanAbi: Int,
    val capabilities: Set<String>,
    val verifiedArtifactMetadata: Boolean,
    val requestContinuousPresentV52: Boolean,
)

data class RuntimeGraphicsPresentSelection(
    val mode: RuntimeGraphicsPresentMode,
    val blocker: String? = null,
) {
    val continuousV52Selected: Boolean
        get() =
            blocker == null &&
                mode == RuntimeGraphicsPresentMode.V52_CONTINUOUS_EXPERIMENTAL

    val usable: Boolean
        get() = blocker == null
}

object RuntimeGraphicsPresentPolicy {
    const val WINE_VULKAN_ABI_V52 = 52
    const val CAPABILITY_CONTINUOUS_PRESENT_V52 =
        "pocketpc.vulkan.continuous-present.v52"
    const val ENV_CONTINUOUS_PRESENT_V52 =
        "POCKETPC_VULKAN_CONTINUOUS_PRESENT_V52"

    const val BLOCKER_DECLARATION_UNVERIFIED =
        "VULKAN_CONTINUOUS_PRESENT_V52_DECLARATION_UNVERIFIED"
    const val BLOCKER_RUNTIME_IDENTITY_MISMATCH =
        "VULKAN_CONTINUOUS_PRESENT_V52_RUNTIME_IDENTITY_MISMATCH"
    const val BLOCKER_WINE_VULKAN_ABI_MISMATCH =
        "VULKAN_CONTINUOUS_PRESENT_V52_WINE_VULKAN_ABI_MISMATCH"
    const val BLOCKER_CAPABILITY_MISSING =
        "VULKAN_CONTINUOUS_PRESENT_V52_CAPABILITY_MISSING"

    fun select(
        expectedRuntimeIdentity: String,
        declaration: RuntimeGraphicsGuestDeclaration?,
    ): RuntimeGraphicsPresentSelection {
        if (declaration?.requestContinuousPresentV52 != true) {
            return RuntimeGraphicsPresentSelection(
                mode = RuntimeGraphicsPresentMode.V51_ONE_SHOT,
            )
        }

        val blocker =
            when {
                !declaration.verifiedArtifactMetadata ->
                    BLOCKER_DECLARATION_UNVERIFIED
                declaration.runtimeIdentity != expectedRuntimeIdentity ->
                    BLOCKER_RUNTIME_IDENTITY_MISMATCH
                declaration.wineVulkanAbi != WINE_VULKAN_ABI_V52 ->
                    BLOCKER_WINE_VULKAN_ABI_MISMATCH
                CAPABILITY_CONTINUOUS_PRESENT_V52 !in declaration.capabilities ->
                    BLOCKER_CAPABILITY_MISSING
                else -> null
            }

        return RuntimeGraphicsPresentSelection(
            mode =
                if (blocker == null) {
                    RuntimeGraphicsPresentMode.V52_CONTINUOUS_EXPERIMENTAL
                } else {
                    RuntimeGraphicsPresentMode.V51_ONE_SHOT
                },
            blocker = blocker,
        )
    }

    fun launchEnvironment(
        selection: RuntimeGraphicsPresentSelection,
    ): Map<String, String> =
        if (selection.continuousV52Selected) {
            mapOf(ENV_CONTINUOUS_PRESENT_V52 to "1")
        } else {
            emptyMap()
        }
}
