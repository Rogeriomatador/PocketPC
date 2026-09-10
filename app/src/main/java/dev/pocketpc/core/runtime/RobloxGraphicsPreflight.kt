package dev.pocketpc.core.runtime

import android.content.Context

data class RobloxGraphicsPreflight(
    val nativeHost: NativeHostStatus,
    val crossProcessEvidence: HardwareBufferCrossProcessEvidence?,
    val wsiFoundation: PocketPcVulkanWsiFoundationStatus,
    val wsiCapabilities: VulkanWsiCapabilitySnapshot?,
    val capabilityProbeSucceeded: Boolean,
    val androidSurfaceRouteAdvertised: Boolean,
    val headlessSurfaceRouteAdvertised: Boolean,
    val ahardwareBufferExternalMemoryAdvertised: Boolean,
    val wsiImplemented: Boolean,
    val readyForWsiIntegrationTest: Boolean,
    val readyForRobloxGraphics: Boolean,
    val blockers: List<String>,
    val detail: String,
)

object RobloxGraphicsPreflightCoordinator {
    const val BLOCKER_WSI_CAPABILITY_PROBE =
        "ROBLOX_VULKAN_WSI_CAPABILITY_PROBE_NOT_PROVEN"
    const val BLOCKER_WSI_SURFACE_ROUTE =
        "ROBLOX_VULKAN_WSI_SURFACE_ROUTE_NOT_ADVERTISED"
    const val BLOCKER_AHB_EXTERNAL_MEMORY =
        "ROBLOX_VULKAN_AHB_EXTERNAL_MEMORY_NOT_ADVERTISED"

    fun run(
        context: Context,
        onComplete: (RobloxGraphicsPreflight) -> Unit,
    ) {
        val appContext = context.applicationContext
        val nativeHost = NativeRuntimeHost.status(appContext)

        if (!nativeHost.loaded) {
            onComplete(
                buildResult(
                    nativeHost = nativeHost,
                    crossProcessEvidence = null,
                ),
            )
            return
        }

        HardwareBufferCrossProcessProbe.run(
            context = appContext,
        ) { evidence ->
            onComplete(
                buildResult(
                    nativeHost = nativeHost,
                    crossProcessEvidence = evidence,
                ),
            )
        }
    }

    fun buildResult(
        nativeHost: NativeHostStatus,
        crossProcessEvidence: HardwareBufferCrossProcessEvidence?,
    ): RobloxGraphicsPreflight {
        val foundation =
            PocketPcVulkanWsiFoundationProbe.assess(
                nativeHost = nativeHost,
                crossProcessEvidence = crossProcessEvidence,
            )
        val capabilities =
            VulkanWsiCapabilityProbeParser.parse(
                nativeHost.vulkanWsiCapabilityProbe,
            )
        val capabilityProbeSucceeded =
            capabilities?.probeSucceeded == true
        val androidSurfaceRoute =
            capabilities?.androidSurfaceRouteAdvertised == true
        val headlessSurfaceRoute =
            capabilities?.headlessSurfaceRouteAdvertised == true
        val anySurfaceRoute =
            androidSurfaceRoute || headlessSurfaceRoute
        val ahbExternalMemory =
            capabilities
                ?.ahardwareBufferExternalMemoryAdvertised == true

        val blockers = mutableListOf<String>()
        blockers += foundation.blockers
        if (!capabilityProbeSucceeded) {
            blockers += BLOCKER_WSI_CAPABILITY_PROBE
        }
        if (capabilityProbeSucceeded && !anySurfaceRoute) {
            blockers += BLOCKER_WSI_SURFACE_ROUTE
        }
        if (capabilityProbeSucceeded && !ahbExternalMemory) {
            blockers += BLOCKER_AHB_EXTERNAL_MEMORY
        }
        if (!PocketPcVulkanWsiContract.implemented) {
            blockers += PocketPcVulkanWsiContract.blocker
        }

        val canEnterWsiTest =
            PocketPcVulkanWsiContract
                .canEnterWsiIntegrationTest(foundation) &&
                capabilityProbeSucceeded &&
                anySurfaceRoute &&
                ahbExternalMemory

        val detail =
            when {
                !foundation.readyForWsiImplementation ->
                    "Transporte gráfico ainda não provou a fundação cross-process necessária ao WSI."

                !capabilityProbeSucceeded ->
                    "A fundação de transporte está disponível, mas as capacidades Vulkan WSI desta GPU ainda não foram provadas."

                !anySurfaceRoute ->
                    "O probe Vulkan respondeu, mas não anunciou uma rota de surface + swapchain utilizável pelo experimento atual."

                !ahbExternalMemory ->
                    "A GPU anunciou uma rota de surface, mas não anunciou VK_ANDROID_external_memory_android_hardware_buffer."

                !PocketPcVulkanWsiContract.implemented ->
                    "A GPU anuncia os pré-requisitos pesquisados, mas o Wine Vulkan WSI do PocketPC continua não implementado."

                !canEnterWsiTest ->
                    "O backend WSI existe, mas os gates para o teste de integração ainda não estão completos."

                else ->
                    "Fundação, capacidades e implementação WSI estão aptas a entrar no teste de integração; isso ainda não é prova de Present nem de Roblox."
            }

        return RobloxGraphicsPreflight(
            nativeHost = nativeHost,
            crossProcessEvidence = crossProcessEvidence,
            wsiFoundation = foundation,
            wsiCapabilities = capabilities,
            capabilityProbeSucceeded = capabilityProbeSucceeded,
            androidSurfaceRouteAdvertised = androidSurfaceRoute,
            headlessSurfaceRouteAdvertised = headlessSurfaceRoute,
            ahardwareBufferExternalMemoryAdvertised = ahbExternalMemory,
            wsiImplemented = PocketPcVulkanWsiContract.implemented,
            readyForWsiIntegrationTest = canEnterWsiTest,
            readyForRobloxGraphics = false,
            blockers = blockers.distinct(),
            detail = detail,
        )
    }
}
