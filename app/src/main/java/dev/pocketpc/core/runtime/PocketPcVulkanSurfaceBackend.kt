package dev.pocketpc.core.runtime

/**
 * Candidate host-surface routes for Wine's Vulkan v47 p_vulkan_surface_create.
 *
 * AHardwareBuffer is intentionally absent: it is an image/memory transport
 * primitive, not a VkSurfaceKHR. A backend is only selectable here when its
 * required host capability is advertised; that still does not make it
 * implemented or safe to execute.
 */
enum class PocketPcVulkanSurfaceBackendKind {
    ANDROID_NATIVE_SURFACE,
    HEADLESS_SURFACE_SHIM,
    VIRTUAL_WSI,
    NONE,
}

data class PocketPcVulkanSurfaceBackendCandidate(
    val kind: PocketPcVulkanSurfaceBackendKind,
    val capabilityAdvertised: Boolean,
    val implemented: Boolean,
    val blocker: String,
    val detail: String,
) {
    val runnable: Boolean
        get() = capabilityAdvertised && implemented
}

data class PocketPcVulkanSurfaceBackendSelection(
    val selected: PocketPcVulkanSurfaceBackendCandidate,
    val candidates: List<PocketPcVulkanSurfaceBackendCandidate>,
    val ahardwareBufferIsSurface: Boolean = false,
) {
    val runnable: Boolean
        get() = selected.runnable && !ahardwareBufferIsSurface
}

object PocketPcVulkanSurfaceBackendProbe {
    const val BLOCKER_ANDROID_WINDOW_TRANSPORT =
        "VULKAN_ANDROID_NATIVE_WINDOW_TRANSPORT_NOT_IMPLEMENTED"
    const val BLOCKER_HEADLESS_PRESENT_CAPTURE =
        "VULKAN_HEADLESS_PRESENT_CAPTURE_NOT_IMPLEMENTED"
    const val BLOCKER_VIRTUAL_WSI =
        "VULKAN_VIRTUAL_WSI_NOT_IMPLEMENTED"
    const val BLOCKER_NO_SURFACE_BACKEND =
        "VULKAN_SURFACE_BACKEND_NOT_AVAILABLE"

    fun assess(
        capabilities: VulkanWsiCapabilitySnapshot?,
    ): PocketPcVulkanSurfaceBackendSelection {
        val probeSucceeded =
            capabilities?.probeSucceeded == true

        val android =
            PocketPcVulkanSurfaceBackendCandidate(
                kind = PocketPcVulkanSurfaceBackendKind.ANDROID_NATIVE_SURFACE,
                capabilityAdvertised =
                    probeSucceeded &&
                        capabilities?.khrSurface == true &&
                        capabilities.khrAndroidSurface &&
                        capabilities.khrSwapchain,
                implemented = false,
                blocker = BLOCKER_ANDROID_WINDOW_TRANSPORT,
                detail =
                    "VK_KHR_android_surface precisa de um ANativeWindow válido no processo que cria a VkSurfaceKHR; ponteiros de outro processo não são transportáveis.",
            )

        val headless =
            PocketPcVulkanSurfaceBackendCandidate(
                kind = PocketPcVulkanSurfaceBackendKind.HEADLESS_SURFACE_SHIM,
                capabilityAdvertised =
                    probeSucceeded &&
                        capabilities?.khrSurface == true &&
                        capabilities.extHeadlessSurface &&
                        capabilities.khrSwapchain,
                implemented = false,
                blocker = BLOCKER_HEADLESS_PRESENT_CAPTURE,
                detail =
                    "VK_EXT_headless_surface pode satisfazer uma surface somente se o PocketPC também implementar um caminho comprovado para obter/apresentar os frames; a extensão sozinha não torna o frame visível.",
            )

        val virtual =
            PocketPcVulkanSurfaceBackendCandidate(
                kind = PocketPcVulkanSurfaceBackendKind.VIRTUAL_WSI,
                capabilityAdvertised = probeSucceeded,
                implemented = false,
                blocker = BLOCKER_VIRTUAL_WSI,
                detail =
                    "Backend virtual exigirá interceptação/implementação coerente de surface, swapchain e Present; um handle VkSurfaceKHR fictício isolado é proibido.",
            )

        val candidates =
            listOf(android, headless, virtual)
        val selected =
            candidates.firstOrNull { it.runnable }
                ?: PocketPcVulkanSurfaceBackendCandidate(
                    kind = PocketPcVulkanSurfaceBackendKind.NONE,
                    capabilityAdvertised = false,
                    implemented = false,
                    blocker = BLOCKER_NO_SURFACE_BACKEND,
                    detail =
                        "Nenhum backend de VkSurfaceKHR do PocketPC está implementado e validado para execução.",
                )

        return PocketPcVulkanSurfaceBackendSelection(
            selected = selected,
            candidates = candidates,
            ahardwareBufferIsSurface = false,
        )
    }
}
