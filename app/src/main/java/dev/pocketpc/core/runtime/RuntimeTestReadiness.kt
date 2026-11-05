package dev.pocketpc.core.runtime

data class RuntimeTestPrerequisite(
    val id: String,
    val label: String,
    val ready: Boolean,
    val detail: String,
)

data class RuntimeTestReadiness(
    val prerequisites:
        List<RuntimeTestPrerequisite>,
) {
    val corePrerequisites:
        List<RuntimeTestPrerequisite>
        get() =
            prerequisites.filter {
                it.id != "dxvk"
            }

    val firstCoreBlocker:
        RuntimeTestPrerequisite?
        get() =
            corePrerequisites
                .firstOrNull {
                    !it.ready
                }

    val coreRuntimeReady:
        Boolean
        get() =
            firstCoreBlocker == null

    val firstD3dBlocker:
        RuntimeTestPrerequisite?
        get() =
            prerequisites
                .firstOrNull {
                    !it.ready
                }

    val d3dRuntimeReady:
        Boolean
        get() =
            firstD3dBlocker == null

    /*
     * Compatibility alias for callers that still describe the complete
     * diagnostic stack. Core/GDI callers must use firstCoreBlocker instead.
     */
    val firstBlocker:
        RuntimeTestPrerequisite?
        get() = firstD3dBlocker
}

object RuntimeTestReadinessProbe {
    fun assess(
        nativeHost:
            NativeHostStatus,
        substrate:
            ExecutionSubstrateStatus,
        runtime:
            InstalledRuntime?,
        installedTools:
            List<InstalledGuestTool>,
        deployedLayers:
            List<DeployedWindowsRuntimeLayer>,
    ): RuntimeTestReadiness {
        val rootfsReady =
            runtime?.let {
                RootfsExecutionReadinessProbe
                    .assess(it)
                    .ready
            } == true
        val productionProotReady =
            substrate.prootReady
        val validationProotReady =
            productionProotReady ||
                substrate.deviceValidationReady
        val rootfsValidationReady =
            validationProotReady &&
                rootfsReady
        val productionPcRuntimeUnlocked =
            productionProotReady &&
                rootfsReady
        val toolIds =
            installedTools
                .map {
                    it.manifest.id
                }
                .toSet()
        val layerIds =
            deployedLayers
                .map {
                    it.manifest.id
                }
                .toSet()
        val box64Installed =
            "box64" in toolIds
        val wineInstalled =
            "wine" in toolIds
        val dxvkDeployed =
            "dxvk" in layerIds
        val box64Ready =
            productionPcRuntimeUnlocked &&
                box64Installed
        val wineReady =
            productionPcRuntimeUnlocked &&
                wineInstalled
        val dxvkReady =
            productionPcRuntimeUnlocked &&
                dxvkDeployed
        val validationOnly =
            substrate.deviceValidationReady &&
                !productionProotReady

        return RuntimeTestReadiness(
            prerequisites =
                listOf(
                    RuntimeTestPrerequisite(
                        id =
                            "native-host",
                        label =
                            "Host ARM64",
                        ready =
                            nativeHost.loaded,
                        detail =
                            if (
                                nativeHost.loaded
                            ) {
                                "Biblioteca nativa carregada."
                            } else {
                                "Host nativo ainda não carregou."
                            },
                    ),
                    RuntimeTestPrerequisite(
                        id = "proot",
                        label = "PRoot",
                        ready =
                            validationProotReady,
                        detail =
                            when {
                                productionProotReady ->
                                    "Substrate aprovado, íntegro e executável."

                                substrate.deviceValidationReady ->
                                    "Candidato PRoot atestado para validação física de Shell/Rootfs; produção permanece bloqueada."

                                else ->
                                    "PRoot ainda não está aprovado/verificado nesta instalação."
                            },
                    ),
                    RuntimeTestPrerequisite(
                        id = "rootfs",
                        label =
                            "Ubuntu rootfs",
                        ready =
                            rootfsValidationReady,
                        detail =
                            when {
                                rootfsValidationReady ->
                                    "Rootfs e links preparados para o gate atual."

                                rootfsReady ->
                                    "Rootfs preparado, aguardando um PRoot autorizado para este gate."

                                else ->
                                    "Rootfs ainda não está pronto para execução."
                            },
                    ),
                    RuntimeTestPrerequisite(
                        id = "box64",
                        label = "Box64",
                        ready =
                            box64Ready,
                        detail =
                            when {
                                validationOnly ->
                                    "BLOQUEADO neste candidato: somente Shell/Rootfs estão autorizados."

                                box64Ready ->
                                    "Pacote Box64 instalado e atestado."

                                !box64Installed ->
                                    "Pacote Box64 ainda não está instalado."

                                else ->
                                    "Box64 aguarda PRoot/rootfs aprovados para o runtime PC."
                            },
                    ),
                    RuntimeTestPrerequisite(
                        id = "wine",
                        label = "Wine 11",
                        ready =
                            wineReady,
                        detail =
                            when {
                                validationOnly ->
                                    "BLOQUEADO neste candidato: Wine não é autorizado antes dos gates Shell/Rootfs."

                                wineReady ->
                                    "Pacote Wine instalado e atestado."

                                !wineInstalled ->
                                    "Wine é necessário para os probes Win64."

                                else ->
                                    "Wine aguarda PRoot/rootfs aprovados para o runtime PC."
                            },
                    ),
                    RuntimeTestPrerequisite(
                        id = "dxvk",
                        label = "DXVK",
                        ready =
                            dxvkReady,
                        detail =
                            when {
                                validationOnly ->
                                    "BLOQUEADO neste candidato: Direct3D permanece fora do gate Shell/Rootfs."

                                dxvkReady ->
                                    "DXVK implantado neste prefixo."

                                !dxvkDeployed ->
                                    "DXVK não bloqueia o core/GDI; ele passa a ser obrigatório apenas nos probes D3D11."

                                else ->
                                    "DXVK aguarda PRoot/rootfs aprovados para os probes D3D11."
                            },
                    ),
                ),
        )
    }
}
