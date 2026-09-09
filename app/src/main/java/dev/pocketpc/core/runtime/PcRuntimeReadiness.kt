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
    val controlledAttemptReady: Boolean = false,
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
        windowsStateReady: Boolean = false,
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
                    id = "wine-display-driver",
                    label = "Janelas Wine → PocketPC",
                    state =
                        if (
                            probeEvidence
                                ?.winePocketPcWindowSmokePassed ==
                            true
                        ) {
                            PcRuntimeStageState.READY
                        } else {
                            PcRuntimeStageState.BLOCKED
                        },
                    detail =
                        if (
                            probeEvidence
                                ?.winePocketPcWindowSmokePassed ==
                            true
                        ) {
                            "winepocketpc.drv carregou uma janela GDI, apresentou pixels e recebeu mouse/teclado através da bridge com evidência vinculada à identidade atual."
                        } else {
                            "Ainda falta comprovar o carregamento real de winepocketpc.drv com janela, framebuffer e input de volta ao Win32."
                        },
                ),
                PcRuntimeStage(
                    id = "windows-state",
                    label = "Filesystem, registro e processos Windows",
                    state =
                        if (
                            probeEvidence?.wineSmokePassed ==
                                true &&
                            probeEvidence.windowsProcessSmokePassed &&
                            windowsStateReady
                        ) {
                            PcRuntimeStageState.READY
                        } else {
                            PcRuntimeStageState.BLOCKED
                        },
                    detail =
                        when {
                            probeEvidence?.wineSmokePassed !=
                                true ->
                                "O Wine ainda precisa passar pelo smoke Win64 antes do estado Windows ser aceito."
                            !probeEvidence.windowsProcessSmokePassed ->
                                "O Wine passou, mas CreateProcess/IPC por pipe ainda precisa ser comprovado."
                            windowsStateReady ->
                                "Prefixo Wine válido e processo filho/IPC por pipe comprovados."
                            else ->
                                "Wine e processo/IPC passaram, mas o prefixo não possui a estrutura Windows esperada."
                        },
                ),
                PcRuntimeStage(
                    id = "graphics-bridge",
                    label = "Direct3D → Vulkan",
                    state =
                        if (
                            probeEvidence?.d3d11SmokePassed ==
                                true &&
                            probeEvidence
                                .graphicsPresentationSmokePassed
                        ) {
                            PcRuntimeStageState.READY
                        } else {
                            PcRuntimeStageState.BLOCKED
                        },
                    detail =
                        when {
                            probeEvidence?.d3d11SmokePassed !=
                                true ->
                                "DXVK/vkd3d possuem supply-chain e implantação preparada, mas o smoke D3D11→Vulkan atual ainda não foi comprovado."
                            !probeEvidence.graphicsPresentationSmokePassed ->
                                "D3D11 criou dispositivo via DXVK, mas janela/swapchain/Present ainda não foram comprovados."
                            else ->
                                "D3D11 e swapchain/Present passaram com evidência vinculada ao rootfs, Box64, Wine e DLLs DXVK atuais."
                        },
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
                            val displayBridge =
                                if (
                                    probeEvidence
                                        ?.displayBridgeSmokePassed ==
                                    true
                                ) "AUTH_PASS" else "PENDING"
                            val winsock =
                                if (
                                    probeEvidence?.winsockSmokePassed ==
                                    true
                                ) "PASS_LOCAL_API" else "PENDING"
                            val winmm =
                                if (
                                    probeEvidence?.winmmAudioApiSmokePassed ==
                                    true
                                ) "API_PASS" else "PENDING"
                            val rawInput =
                                if (
                                    probeEvidence?.rawInputApiSmokePassed ==
                                    true
                                ) "API_PASS" else "PENDING"
                            "Host Android: áudio=${ioHost.audioOutputCount} saída(s), " +
                                "teclados=${ioHost.keyboardCount}, mouses=${ioHost.mouseCount}, " +
                                "gamepads=${ioHost.gamepadCount}, internet=$networkState. " +
                                "Bridge x86↔Android=$displayBridge. " +
                                "Wine: Winsock=$winsock, WinMM=$winmm, RawInput=$rawInput. " +
                                "Esses smokes não provam streaming de áudio, eventos de input nem internet externa."
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

        val controlledAttemptStageIds =
            setOf(
                "native-arm64-host",
                "linux-userspace",
                "rootfs",
                "x86-64-translation",
                "win32-compat",
                "wine-display-driver",
                "windows-state",
                "graphics-bridge",
            )
        val controlledAttemptReady =
            stages
                .filter {
                    it.id in
                        controlledAttemptStageIds
                }
                .all {
                    it.state ==
                        PcRuntimeStageState.READY
                } &&
                probeEvidence
                    ?.displayBridgeSmokePassed ==
                    true

        return PcRuntimeReadiness(
            target = "Windows x64 em Android ARM64",
            stages = stages,
            controlledAttemptReady =
                controlledAttemptReady,
            executableReady =
                stages.all {
                    it.state ==
                        PcRuntimeStageState.READY
                },
        )
    }
}
