package dev.pocketpc.core.runtime

/**
 * Candidate host-surface routes for Wine's Vulkan v47 p_vulkan_surface_create.
 *
 * AHardwareBuffer is intentionally absent: it is an image/memory transport
 * primitive, not a VkSurfaceKHR. Diagnostic headless execution is represented
 * separately from visible production presentation so it can never promote
 * Roblox/DXVK visible readiness by accident.
 */
enum class PocketPcVulkanSurfaceBackendKind {
    ANDROID_NATIVE_SURFACE,
    HEADLESS_DIAGNOSTIC,
    HEADLESS_SURFACE_SHIM,
    VIRTUAL_WSI,
    NONE,
}

data class PocketPcVulkanSurfaceBackendCandidate(
    val kind: PocketPcVulkanSurfaceBackendKind,
    val capabilityAdvertised: Boolean,
    val implemented: Boolean,
    val visible: Boolean,
    val blocker: String,
    val detail: String,
) {
    val runnable: Boolean
        get() = capabilityAdvertised && implemented

    val productionRunnable: Boolean
        get() = runnable && visible
}

data class PocketPcVulkanSurfaceBackendSelection(
    val selected: PocketPcVulkanSurfaceBackendCandidate,
    val candidates: List<PocketPcVulkanSurfaceBackendCandidate>,
    val ahardwareBufferIsSurface: Boolean = false,
) {
    /** Visible/production surface only. */
    val runnable: Boolean
        get() = selected.productionRunnable && !ahardwareBufferIsSurface

    val diagnosticCandidate: PocketPcVulkanSurfaceBackendCandidate?
        get() = candidates.firstOrNull {
            it.kind == PocketPcVulkanSurfaceBackendKind.HEADLESS_DIAGNOSTIC
        }

    val diagnosticRunnable: Boolean
        get() = diagnosticCandidate?.runnable == true
}

object PocketPcVulkanSurfaceBackendProbe {
    const val BLOCKER_ANDROID_WINDOW_TRANSPORT =
        "VULKAN_ANDROID_NATIVE_WINDOW_TRANSPORT_NOT_IMPLEMENTED"
    const val BLOCKER_HEADLESS_DIAGNOSTIC_CAPABILITY =
        "VULKAN_HEADLESS_DIAGNOSTIC_CAPABILITY_NOT_AVAILABLE"
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
                visible = true,
                blocker = BLOCKER_ANDROID_WINDOW_TRANSPORT,
                detail =
                    "VK_KHR_android_surface precisa de um ANativeWindow válido no processo que cria a VkSurfaceKHR; ponteiros de outro processo não são transportáveis.",
            )

        val headlessCapability =
            probeSucceeded &&
                capabilities?.khrSurface == true &&
                capabilities.extHeadlessSurface &&
                capabilities.khrSwapchain

        val headlessDiagnostic =
            PocketPcVulkanSurfaceBackendCandidate(
                kind = PocketPcVulkanSurfaceBackendKind.HEADLESS_DIAGNOSTIC,
                capabilityAdvertised = headlessCapability,
                implemented =
                    PocketPcVulkanWsiContract
                        .headlessDiagnosticSurfaceImplemented,
                visible = false,
                blocker = BLOCKER_HEADLESS_DIAGNOSTIC_CAPABILITY,
                detail =
                    "Backend opt-in de diagnóstico: pode provar surface/swapchain Vulkan sem produzir frame visível. Nunca satisfaz o gate de apresentação do Roblox.",
            )

        val headlessVisibleShim =
            PocketPcVulkanSurfaceBackendCandidate(
                kind = PocketPcVulkanSurfaceBackendKind.HEADLESS_SURFACE_SHIM,
                capabilityAdvertised = headlessCapability,
                implemented = false,
                visible = true,
                blocker = BLOCKER_HEADLESS_PRESENT_CAPTURE,
                detail =
                    "Uma surface headless só pode virar backend visível depois de existir captura/apresentação comprovada dos frames no host Android.",
            )

        val virtual =
            PocketPcVulkanSurfaceBackendCandidate(
                kind = PocketPcVulkanSurfaceBackendKind.VIRTUAL_WSI,
                capabilityAdvertised = probeSucceeded,
                implemented = false,
                visible = true,
                blocker = BLOCKER_VIRTUAL_WSI,
                detail =
                    "Backend virtual exigirá interceptação/implementação coerente de surface, swapchain e Present; um handle VkSurfaceKHR fictício isolado é proibido.",
            )

        val candidates =
            listOf(
                android,
                headlessDiagnostic,
                headlessVisibleShim,
                virtual,
            )
        val selected =
            candidates.firstOrNull { it.productionRunnable }
                ?: PocketPcVulkanSurfaceBackendCandidate(
                    kind = PocketPcVulkanSurfaceBackendKind.NONE,
                    capabilityAdvertised = false,
                    implemented = false,
                    visible = true,
                    blocker = BLOCKER_NO_SURFACE_BACKEND,
                    detail =
                        "Nenhum backend de VkSurfaceKHR visível do PocketPC está implementado e validado para execução.",
                )

        return PocketPcVulkanSurfaceBackendSelection(
            selected = selected,
            candidates = candidates,
            ahardwareBufferIsSurface = false,
        )
    }
}
