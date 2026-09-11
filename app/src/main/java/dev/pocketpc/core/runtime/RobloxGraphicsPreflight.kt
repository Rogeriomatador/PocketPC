package dev.pocketpc.core.runtime

import android.content.Context

data class RobloxGraphicsPreflight(
    val nativeHost: NativeHostStatus,
    val crossProcessEvidence: HardwareBufferCrossProcessEvidence?,
    val wsiFoundation: PocketPcVulkanWsiFoundationStatus,
    val wsiCapabilities: VulkanWsiCapabilitySnapshot?,
    val externalResourceCapabilities: VulkanExternalResourceSnapshot?,
    val canonicalAhbImport: VulkanAhardwareBufferImportSnapshot?,
    val guestTransportPlan: GuestGraphicsTransportPlan,
    val surfaceBackend: PocketPcVulkanSurfaceBackendSelection,
    val capabilityProbeSucceeded: Boolean,
    val externalResourceProbeSucceeded: Boolean,
    val androidSurfaceRouteAdvertised: Boolean,
    val headlessSurfaceRouteAdvertised: Boolean,
    val ahardwareBufferExternalMemoryAdvertised: Boolean,
    val canonicalAhbImportQuerySupported: Boolean,
    val headlessDiagnosticRunnable: Boolean,
    val surfaceBackendRunnable: Boolean,
    val wsiImplemented: Boolean,
    val readyForWsiIntegrationTest: Boolean,
    val readyForRobloxGraphics: Boolean,
    val blockers: List<String>,
    val detail: String,
)

object RobloxGraphicsPreflightCoordinator {
    const val BLOCKER_WSI_CAPABILITY_PROBE =
        "ROBLOX_VULKAN_WSI_CAPABILITY_PROBE_NOT_PROVEN"
    const val BLOCKER_EXTERNAL_RESOURCE_PROBE =
        "ROBLOX_VULKAN_EXTERNAL_RESOURCE_PROBE_NOT_PROVEN"
    const val BLOCKER_WSI_SURFACE_ROUTE =
        "ROBLOX_VULKAN_WSI_SURFACE_ROUTE_NOT_ADVERTISED"
    const val BLOCKER_AHB_EXTERNAL_MEMORY =
        "ROBLOX_VULKAN_AHB_EXTERNAL_MEMORY_NOT_ADVERTISED"
    const val BLOCKER_SURFACE_BACKEND =
        "ROBLOX_VULKAN_VISIBLE_SURFACE_BACKEND_NOT_IMPLEMENTED"
    const val BLOCKER_HEADLESS_DIAGNOSTIC_ONLY =
        "ROBLOX_VULKAN_HEADLESS_DIAGNOSTIC_NOT_VISIBLE"

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
        val externalResources =
            VulkanExternalResourceProbeParser.parse(
                nativeHost.vulkanExternalResourceProbe,
            )
        val canonicalAhb =
            VulkanAhardwareBufferImportProbeParser.parse(
                nativeHost.vulkanAhardwareBufferImportProbe,
            )
        val guestTransportPlan =
            GuestGraphicsTransportPlanner.plan(
                snapshot = externalResources,
                ahbImportSnapshot = canonicalAhb,
            )
        val surfaceBackend =
            PocketPcVulkanSurfaceBackendProbe.assess(
                capabilities,
            )

        val capabilityProbeSucceeded =
            capabilities?.probeSucceeded == true
        val externalResourceProbeSucceeded =
            externalResources?.probeSucceeded == true
        val androidSurfaceRoute =
            capabilities?.androidSurfaceRouteAdvertised == true
        val headlessSurfaceRoute =
            capabilities?.headlessSurfaceRouteAdvertised == true
        val anySurfaceRoute =
            androidSurfaceRoute || headlessSurfaceRoute
        val ahbExternalMemory =
            capabilities
                ?.ahardwareBufferExternalMemoryAdvertised == true
        val canonicalAhbReady =
            canonicalAhb?.canonicalImportQuerySupported == true
        val headlessDiagnosticRunnable =
            surfaceBackend.diagnosticRunnable
        val surfaceBackendRunnable =
            surfaceBackend.runnable

        val blockers = mutableListOf<String>()
        blockers += foundation.blockers
        if (!capabilityProbeSucceeded) {
            blockers += BLOCKER_WSI_CAPABILITY_PROBE
        }
        if (!externalResourceProbeSucceeded) {
            blockers += BLOCKER_EXTERNAL_RESOURCE_PROBE
        }
        if (capabilityProbeSucceeded && !anySurfaceRoute) {
            blockers += BLOCKER_WSI_SURFACE_ROUTE
        }
        if (capabilityProbeSucceeded && !ahbExternalMemory) {
            blockers += BLOCKER_AHB_EXTERNAL_MEMORY
        }
        blockers += guestTransportPlan.blockers
        blockers += PocketPcWinePresentBridgeContract.blockers()
        if (!surfaceBackendRunnable) {
            blockers += BLOCKER_SURFACE_BACKEND
            blockers += surfaceBackend.selected.blocker
        }
        if (headlessDiagnosticRunnable && !surfaceBackendRunnable) {
            blockers += BLOCKER_HEADLESS_DIAGNOSTIC_ONLY
        }
        if (!PocketPcVulkanWsiContract.implemented) {
            blockers += PocketPcVulkanWsiContract.blocker
        }

        val canEnterWsiTest =
            PocketPcVulkanWsiContract
                .canEnterWsiIntegrationTest(foundation) &&
                capabilityProbeSucceeded &&
                externalResourceProbeSucceeded &&
                guestTransportPlan.implementationAttemptEligible &&
                anySurfaceRoute &&
                surfaceBackendRunnable

        val readyForRobloxGraphics =
            canEnterWsiTest &&
                PocketPcWinePresentBridgeContract.readyForRobloxGraphics()

        val detail =
            when {
                !foundation.guestGraphicsTransportReady ->
                    "As primitivas host/guest avançaram, mas o recurso gráfico ainda não foi integrado, importado e sincronizado no VkDevice usado pelo Wine/DXVK."

                !foundation.readyForWsiImplementation ->
                    "A fundação gráfica ainda não provou todos os requisitos necessários ao WSI visível."

                !capabilityProbeSucceeded ->
                    "As capacidades Vulkan WSI desta GPU ainda não foram provadas por um registro válido."

                !externalResourceProbeSucceeded ->
                    "O WSI foi sondado, mas o probe de memória/sincronização externa ainda não produziu evidência válida."

                !guestTransportPlan.guestTransportReady ->
                    "Há uma rota candidata de recurso externo, mas receive/import/synchronization do guest ainda não estão integrados e comprovados."

                !PocketPcWinePresentBridgeContract.pixelCopyImplemented ->
                    "O Wine v50 já possui identidade exata da imagem apresentada e contrato de ownership externo em source, porém os pixels da swapchain ainda não são copiados para a imagem compartilhada do PocketPC."

                !PocketPcWinePresentBridgeContract.androidVisiblePresentImplemented ->
                    "A ponte de Present avançou, mas o frame compartilhado ainda não possui apresentação Android visível implementada."

                !PocketPcWinePresentBridgeContract.runtimeExecuted ->
                    "A ponte gráfica existe em source, porém ainda não há execução real registrada do caminho de Present v50."

                !anySurfaceRoute ->
                    "O probe Vulkan respondeu, mas não anunciou uma rota de surface + swapchain para o experimento atual."

                headlessDiagnosticRunnable && !surfaceBackendRunnable ->
                    "A GPU permite o backend headless de diagnóstico do Wine, porém ele é invisível e não satisfaz o Present do Roblox."

                !surfaceBackendRunnable ->
                    "A GPU anuncia extensões, mas o PocketPC ainda não possui um backend VkSurfaceKHR visível e executável."

                !PocketPcVulkanWsiContract.implemented ->
                    "Os pré-requisitos intermediários existem, mas o Wine Vulkan WSI visível do PocketPC continua não implementado."

                !canEnterWsiTest ->
                    "O backend visível existe, mas os gates para o teste de integração ainda não estão completos."

                !PocketPcWinePresentBridgeContract.robloxExecuted ->
                    "A pilha gráfica atingiu os gates intermediários, mas o Roblox Desktop ainda não foi executado e validado nesse runtime."

                else ->
                    "Fundação, transporte, surface visível e WSI podem entrar no teste de integração; a prontidão do Roblox depende de evidência real do runtime."
            }

        return RobloxGraphicsPreflight(
            nativeHost = nativeHost,
            crossProcessEvidence = crossProcessEvidence,
            wsiFoundation = foundation,
            wsiCapabilities = capabilities,
            externalResourceCapabilities = externalResources,
            canonicalAhbImport = canonicalAhb,
            guestTransportPlan = guestTransportPlan,
            surfaceBackend = surfaceBackend,
            capabilityProbeSucceeded = capabilityProbeSucceeded,
            externalResourceProbeSucceeded = externalResourceProbeSucceeded,
            androidSurfaceRouteAdvertised = androidSurfaceRoute,
            headlessSurfaceRouteAdvertised = headlessSurfaceRoute,
            ahardwareBufferExternalMemoryAdvertised = ahbExternalMemory,
            canonicalAhbImportQuerySupported = canonicalAhbReady,
            headlessDiagnosticRunnable = headlessDiagnosticRunnable,
            surfaceBackendRunnable = surfaceBackendRunnable,
            wsiImplemented = PocketPcVulkanWsiContract.implemented,
            readyForWsiIntegrationTest = canEnterWsiTest,
            readyForRobloxGraphics = readyForRobloxGraphics,
            blockers = blockers.distinct(),
            detail = detail,
        )
    }
}
