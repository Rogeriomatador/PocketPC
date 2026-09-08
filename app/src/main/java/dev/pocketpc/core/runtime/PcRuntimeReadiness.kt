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
        probeEvidence: RuntimeProbeEvidenceState? = null,
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
                        if (
                            probeEvidence?.box64SmokePassed ==
                            true
                        ) {
                            PcRuntimeStageState.READY
                        } else {
                            PcRuntimeStageState.BLOCKED
                        },
                    detail =
                        if (
                            probeEvidence?.box64SmokePassed ==
                            true
                        ) {
                            "Box64 executou o ELF x86-64 de smoke com evidência vinculada ao rootfs e pacote atuais."
                        } else {
                            "Box64 v0.4.4 possui pipeline/pacote e smoke x86-64, mas a execução atual ainda não foi comprovada."
                        },
                ),
                PcRuntimeStage(
                    id = "win32-compat",
                    label = "Camada Win32 / NT user-mode",
                    state =
                        if (
                            probeEvidence?.wineSmokePassed ==
                            true
                        ) {
                            PcRuntimeStageState.READY
                        } else {
                            PcRuntimeStageState.BLOCKED
                        },
                    detail =
                        if (
                            probeEvidence?.wineSmokePassed ==
                            true
                        ) {
                            "Wine 11 executou o PE64 de smoke através do Box64 com evidência vinculada às versões atuais."
                        } else {
                            "Wine 11 possui build headless/pacote Win64 preparado, mas o primeiro PE64 ainda não foi comprovado."
                        },
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
                            val networkState =
                                when {
                                    ioHost.networkValidated -> "validada"
                                    ioHost.networkInternetCapable -> "detectada"
                                    else -> "indisponível"
                                }
                            "Host Android: áudio=${ioHost.audioOutputCount} saída(s), " +
                                "teclados=${ioHost.keyboardCount}, mouses=${ioHost.mouseCount}, " +
                                "gamepads=${ioHost.gamepadCount}, internet=$networkState. " +
                                "A ponte Win32 ainda não está implementada."
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
