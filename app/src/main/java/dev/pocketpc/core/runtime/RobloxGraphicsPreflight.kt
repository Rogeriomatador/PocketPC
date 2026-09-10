package dev.pocketpc.core.runtime

import android.content.Context

data class RobloxGraphicsPreflight(
    val nativeHost: NativeHostStatus,
    val crossProcessEvidence: HardwareBufferCrossProcessEvidence?,
    val wsiFoundation: PocketPcVulkanWsiFoundationStatus,
    val wsiImplemented: Boolean,
    val readyForWsiIntegrationTest: Boolean,
    val readyForRobloxGraphics: Boolean,
    val detail: String,
)

object RobloxGraphicsPreflightCoordinator {
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
        val canEnterWsiTest =
            PocketPcVulkanWsiContract
                .canEnterWsiIntegrationTest(foundation)

        val detail =
            when {
                !foundation.readyForWsiImplementation ->
                    "Transporte gráfico ainda não provou a fundação cross-process necessária ao WSI."

                !PocketPcVulkanWsiContract.implemented ->
                    "AHardwareBuffer cross-process está pronto como fundação, mas o Wine Vulkan WSI continua não implementado."

                !canEnterWsiTest ->
                    "O backend WSI existe, mas os gates para o teste de integração ainda não estão completos."

                else ->
                    "Fundação e implementação WSI estão aptas a entrar no teste de integração; isso ainda não é prova de Present nem de Roblox."
            }

        return RobloxGraphicsPreflight(
            nativeHost = nativeHost,
            crossProcessEvidence = crossProcessEvidence,
            wsiFoundation = foundation,
            wsiImplemented = PocketPcVulkanWsiContract.implemented,
            readyForWsiIntegrationTest = canEnterWsiTest,
            readyForRobloxGraphics = false,
            detail = detail,
        )
    }
}
