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
                            substrate.prootReady,
                        detail =
                            if (
                                substrate.prootReady
                            ) {
                                "Substrate aprovado, íntegro e executável."
                            } else {
                                "PRoot ainda não está aprovado/verificado nesta instalação."
                            },
                    ),
                    RuntimeTestPrerequisite(
                        id = "rootfs",
                        label =
                            "Ubuntu rootfs",
                        ready =
                            rootfsReady,
                        detail =
                            if (
                                rootfsReady
                            ) {
                                "Rootfs e links preparados."
                            } else {
                                "Rootfs ainda não está pronto para execução."
                            },
                    ),
                    RuntimeTestPrerequisite(
                        id = "box64",
                        label = "Box64",
                        ready =
                            "box64" in
                                toolIds,
                        detail =
                            if (
                                "box64" in
                                    toolIds
                            ) {
                                "Pacote Box64 instalado e atestado."
                            } else {
                                "Pacote Box64 ainda não está instalado."
                            },
                    ),
                    RuntimeTestPrerequisite(
                        id = "wine",
                        label = "Wine 11",
                        ready =
                            "wine" in
                                toolIds,
                        detail =
                            if (
                                "wine" in
                                    toolIds
                            ) {
                                "Pacote Wine instalado e atestado."
                            } else {
                                "Wine é necessário para os probes Win64."
                            },
                    ),
                    RuntimeTestPrerequisite(
                        id = "dxvk",
                        label = "DXVK",
                        ready =
                            "dxvk" in
                                layerIds,
                        detail =
                            if (
                                "dxvk" in
                                    layerIds
                            ) {
                                "DXVK implantado neste prefixo."
                            } else {
                                "DXVK não bloqueia o core/GDI; ele passa a ser obrigatório apenas nos probes D3D11."
                            },
                    ),
                ),
        )
    }
}
