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
        preparedRuntimeCount: Int = installedRuntimeCount,
        ioHost: RuntimeIoHostCapabilities? = null,
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
                        if (preparedRuntimeCount > 0) {
                            PcRuntimeStageState.READY
                        } else {
                            PcRuntimeStageState.BLOCKED
                        },
                    detail =
                        when {
                            preparedRuntimeCount > 0 ->
                                "$preparedRuntimeCount de $installedRuntimeCount rootfs preparado(s) para execução."
                            installedRuntimeCount > 0 ->
                                "$installedRuntimeCount rootfs instalado(s), mas nenhum está preparado para execução."
                            else ->
                                "Nenhum rootfs instalado e validado."
                        },
                ),
                PcRuntimeStage(
                    id = "x86-64-translation",
                    label = "Tradução x86_64 → ARM64",
                    state =
                        PcRuntimeStageState.NOT_IMPLEMENTED,
                    detail =
                        "Box64 v0.4.4 está fixado por commit e possui build AArch64 de revisão. " +
                            "Ainda não está integrado ao rootfs nem executado pelo PocketPC.",
                ),
                PcRuntimeStage(
                    id = "win32-compat",
                    label = "Camada Win32 / NT user-mode",
                    state =
                        PcRuntimeStageState.NOT_IMPLEMENTED,
                    detail =
                        "Wine 11.0 estável está fixado por commit e possui preparação de build Win64. " +
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
                        "DXVK 3.0.2 e vkd3d-proton 3.0.1 estão fixados por commit. " +
                            "Nenhuma ponte Direct3D foi construída ou validada até a GPU Android.",
                ),
                PcRuntimeStage(
                    id = "io-integration",
                    label = "Áudio, input e rede",
                    state =
                        PcRuntimeStageState.NOT_IMPLEMENTED,
                    detail =
                        if (ioHost == null) {
                            "Capacidades Android de áudio/input/rede ainda não foram medidas nesta sessão."
                        } else {
                            "Host Android: áudio=${ioHost.audioOutputCount} saída(s), " +
                                "teclados=${ioHost.keyboardCount}, mouses=${ioHost.mouseCount}, " +
                                "gamepads=${ioHost.gamepadCount}, internet=" +
                                if (ioHost.networkValidated) "validada" else if (ioHost.networkInternetCapable) "detectada" else "indisponível" +
                                ". A ponte Win32 ainda não está implementada."
                        },
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
