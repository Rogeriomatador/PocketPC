package dev.pocketpc.core.runtime

data class HeadlessDxvkDiagnosticPlan(
    val ready: Boolean,
    val argv: List<String>,
    val environment: Map<String, String>,
    val blockers: List<String>,
    val diagnosticOnly: Boolean = true,
    val visiblePresentExpected: Boolean = false,
    val mayPromoteRobloxGraphics: Boolean = false,
)

object HeadlessDxvkDiagnosticPlanner {
    const val PRESENT_SMOKE_EXE =
        "/opt/pocketpc/wine/share/tests/pocketpc-d3d11-present-smoke.exe"
    const val HEADLESS_ENV =
        "POCKETPC_VULKAN_HEADLESS_DIAGNOSTIC"
    const val HEADLESS_ENV_ENABLED = "1"

    fun build(
        prefixPlan: WindowsPrefixPlan,
        capabilities: VulkanWsiCapabilitySnapshot?,
        box64RuntimeValidated: Boolean,
        wineRuntimeValidated: Boolean,
        dxvkLayerReady: Boolean,
    ): HeadlessDxvkDiagnosticPlan {
        val blockers = mutableListOf<String>()

        if (
            !PocketPcVulkanWsiContract
                .headlessDiagnosticSurfaceImplemented
        ) {
            blockers += "VULKAN_HEADLESS_DIAGNOSTIC_NOT_IMPLEMENTED"
        }
        if (
            !PocketPcVulkanWsiContract
                .headlessDiagnosticPresentationSupportImplemented
        ) {
            blockers += "VULKAN_HEADLESS_DIAGNOSTIC_PRESENTATION_SUPPORT_NOT_IMPLEMENTED"
        }
        if (
            !PocketPcVulkanWsiContract
                .headlessDiagnosticExtensionMappingImplemented
        ) {
            blockers += "VULKAN_HEADLESS_DIAGNOSTIC_EXTENSION_MAPPING_NOT_IMPLEMENTED"
        }
        if (capabilities?.probeSucceeded != true) {
            blockers += "VULKAN_WSI_CAPABILITY_PROBE_NOT_READY"
        } else {
            if (!capabilities.khrSurface) {
                blockers += "VK_KHR_SURFACE_NOT_ADVERTISED"
            }
            if (!capabilities.extHeadlessSurface) {
                blockers += "VK_EXT_HEADLESS_SURFACE_NOT_ADVERTISED"
            }
            if (!capabilities.khrSwapchain) {
                blockers += "VK_KHR_SWAPCHAIN_NOT_ADVERTISED"
            }
        }
        if (!dxvkLayerReady) {
            blockers += "DXVK_LAYER_NOT_READY"
        }

        val basePlan =
            WineLaunchPlanner.build(
                prefixPlan = prefixPlan,
                windowsExecutable = PRESENT_SMOKE_EXE,
                box64RuntimeValidated = box64RuntimeValidated,
                wineRuntimeValidated = wineRuntimeValidated,
                enableDxvk = true,
            )
        blockers += basePlan.blockers

        val ready = blockers.isEmpty() && basePlan.ready
        val environment =
            if (ready) {
                LinkedHashMap(basePlan.environment).apply {
                    put(HEADLESS_ENV, HEADLESS_ENV_ENABLED)
                }
            } else {
                emptyMap()
            }

        return HeadlessDxvkDiagnosticPlan(
            ready = ready,
            argv = if (ready) basePlan.argv else emptyList(),
            environment = environment,
            blockers = blockers.distinct(),
            diagnosticOnly = true,
            visiblePresentExpected = false,
            mayPromoteRobloxGraphics = false,
        )
    }
}
