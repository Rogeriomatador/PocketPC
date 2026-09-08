package dev.pocketpc.core.runtime

enum class PcRuntimeStageState {
    READY,
    BLOCKED,
    NOT_IMPLEMENTED,
    UNKNOWN,
}

data class PcRuntimeStage(
    val id: String,
    val label: String,
    val state: PcRuntimeStageState,
    val detail: String,
)

data class PcRuntimeReadiness(
    val target: String,
    val stages: List<PcRuntimeStage>,
    val executableReady: Boolean,
) {
    val readyCount: Int
        get() =
            stages.count {
                it.state ==
                    PcRuntimeStageState.READY
            }
}

object PcRuntimeReadinessProbe {
    fun assess(
        nativeHost: NativeHostStatus,
        substrate: ExecutionSubstrateStatus,
        installedRuntimeCount: Int,
    ): PcRuntimeReadiness {
        val stages =
            listOf(
                PcRuntimeStage(
                    id = "native-arm64-host",
                    label = "Host nativo ARM64",
                    state =
                        if (nativeHost.loaded) {
                            PcRuntimeStageState.READY
                        } else {
                            PcRuntimeStageState.BLOCKED
                        },
                    detail =
                        if (nativeHost.loaded) {
                            "libpocketpc_runtime carregada."
                        } else {
                            "O host nativo não carregou."
                        },
                ),
                PcRuntimeStage(
                    id = "linux-userspace",
                    label = "Substrate / userspace Linux",
                    state =
                        if (substrate.prootReady) {
                            PcRuntimeStageState.READY
                        } else {
                            PcRuntimeStageState.BLOCKED
                        },
                    detail =
                        if (substrate.prootReady) {
                            "Artefatos aprovados e verificados."
                        } else {
                            "PRoot/loader ainda não estão aprovados e prontos."
                        },
                ),
                PcRuntimeStage(
                    id = "rootfs",
                    label = "Rootfs instalado",
                    state =
                        if (installedRuntimeCount > 0) {
                            PcRuntimeStageState.READY
                        } else {
                            PcRuntimeStageState.BLOCKED
                        },
                    detail =
                        if (installedRuntimeCount > 0) {
                            "$installedRuntimeCount runtime(s) de dados instalado(s)."
                        } else {
                            "Nenhum rootfs instalado e validado."
                        },
                ),
                PcRuntimeStage(
                    id = "x86-64-translation",
                    label = "Tradução x86_64 → ARM64",
                    state =
                        PcRuntimeStageState.NOT_IMPLEMENTED,
                    detail =
                        "Candidato técnico: Box64 Android/ARM64. " +
                            "Ainda não está aprovado, empacotado ou executado pelo PocketPC.",
                ),
                PcRuntimeStage(
                    id = "win32-compat",
                    label = "Camada Win32 / NT user-mode",
                    state =
                        PcRuntimeStageState.NOT_IMPLEMENTED,
                    detail =
                        "Candidato técnico: Wine WoW64 sobre o tradutor x86_64. " +
                            "Loader PE, DLLs e APIs Win32 ainda não executam no PocketPC.",
                ),
                PcRuntimeStage(
                    id = "windows-state",
                    label = "Filesystem, registro e processos Windows",
                    state =
                        PcRuntimeStageState.NOT_IMPLEMENTED,
                    detail =
                        "Prefixo Windows, registry, processos filhos e IPC ainda precisam de backend.",
                ),
                PcRuntimeStage(
                    id = "graphics-bridge",
                    label = "Direct3D → Vulkan",
                    state =
                        PcRuntimeStageState.NOT_IMPLEMENTED,
                    detail =
                        "Candidatos: DXVK para D3D9/10/11 e VKD3D para D3D12, " +
                            "mas nenhuma ponte foi validada até a GPU Android.",
                ),
                PcRuntimeStage(
                    id = "io-integration",
                    label = "Áudio, input e rede",
                    state =
                        PcRuntimeStageState.NOT_IMPLEMENTED,
                    detail =
                        "O host Android possui recursos, mas a integração Windows ainda não existe.",
                ),
                PcRuntimeStage(
                    id = "roblox-compatibility",
                    label = "Roblox desktop",
                    state =
                        PcRuntimeStageState.UNKNOWN,
                    detail =
                        "Compatibilidade final depende do runtime completo e das políticas oficiais do jogo; nenhuma proteção será contornada.",
                ),
            )

        return PcRuntimeReadiness(
            target = "Windows x64 em Android ARM64",
            stages = stages,
            executableReady =
                stages.all {
                    it.state ==
                        PcRuntimeStageState.READY
                },
        )
    }
}
