package dev.pocketpc.core.runtime

enum class PcApplicationKind {
    ROBLOX_DESKTOP,
    WINDOWS_INSTALLER,
}

enum class PcApplicationCompatibilityState {
    RUNTIME_BLOCKED,
    RUNTIME_READY_APP_UNVALIDATED,
}

data class PcApplicationCompatibility(
    val fileName: String,
    val kind: PcApplicationKind,
    val displayName: String,
    val state: PcApplicationCompatibilityState,
    val runtimeReady: Boolean,
    val applicationValidated: Boolean,
    val missingRuntimeStages: List<String>,
    val detail: String,
)

object PcApplicationCompatibilityProbe {
    fun assess(
        fileName: String,
        readiness: PcRuntimeReadiness,
    ): PcApplicationCompatibility {
        val cleanName =
            fileName.trim()
                .ifBlank { "programa.exe" }
        val robloxArtifact =
            RobloxWindowsArtifactClassifier
                .classify(cleanName)
        val isSupportedRobloxPlayerArtifact =
            robloxArtifact.recognized &&
                robloxArtifact.channel !=
                    RobloxWindowsChannel.STUDIO

        val kind =
            if (isSupportedRobloxPlayerArtifact) {
                PcApplicationKind.ROBLOX_DESKTOP
            } else {
                PcApplicationKind.WINDOWS_INSTALLER
            }

        val missingStages =
            readiness.stages
                .filter {
                    it.state != PcRuntimeStageState.READY
                }
                .map { it.label }

        val baseRuntimeReady =
            readiness.controlledAttemptReady
        val artifactRuntimeReady =
            baseRuntimeReady &&
                robloxArtifact.kind !=
                    RobloxWindowsArtifactKind.STORE_PACKAGE
        val state =
            if (artifactRuntimeReady) {
                PcApplicationCompatibilityState
                    .RUNTIME_READY_APP_UNVALIDATED
            } else {
                PcApplicationCompatibilityState
                    .RUNTIME_BLOCKED
            }

        val displayName =
            when (kind) {
                PcApplicationKind.ROBLOX_DESKTOP ->
                    "Roblox desktop"
                PcApplicationKind.WINDOWS_INSTALLER ->
                    "Aplicativo Windows"
            }

        val detail =
            when {
                robloxArtifact.kind ==
                    RobloxWindowsArtifactKind.STORE_PACKAGE ->
                    "O pacote Roblox da Microsoft Store foi reconhecido, " +
                        "mas MSIX/AppX ainda não possui instalador implementado " +
                        "no PocketPC. O arquivo permanece bloqueado."

                robloxArtifact.kind ==
                    RobloxWindowsArtifactKind.STUDIO_EXECUTABLE ->
                    "Roblox Studio foi identificado separadamente. " +
                        "Este fluxo é do Player e não promove Studio como cliente de jogo."

                robloxArtifact.kind ==
                    RobloxWindowsArtifactKind.UNKNOWN_ROBLOX ->
                    "O nome menciona Roblox, mas não corresponde ao Player, " +
                        "bootstrapper ou pacote Store reconhecido. A execução " +
                        "não será promovida apenas pelo nome do arquivo."

                !artifactRuntimeReady &&
                    kind == PcApplicationKind.ROBLOX_DESKTOP ->
                    "Um artefato conhecido do Roblox Player foi reconhecido, " +
                        "mas o runtime Windows x64 ainda não está pronto. " +
                        "O PocketPC não tentará iniciar o jogo nem contornar " +
                        "proteções enquanto tradução x86_64, Win32, gráficos, " +
                        "áudio/input e processos não estiverem validados."

                !artifactRuntimeReady ->
                    "O arquivo foi reconhecido como software de PC, " +
                        "mas a execução continua bloqueada até todos " +
                        "os estágios do runtime Windows passarem."

                kind == PcApplicationKind.ROBLOX_DESKTOP ->
                    "O runtime base está pronto para tentativa controlada, " +
                        "mas Roblox ainda precisa de evidência específica de " +
                        "processo, janela, Direct3D/Present, rede, áudio e input. " +
                        "Nenhuma compatibilidade final é presumida."

                else ->
                    "O runtime base está pronto, mas este aplicativo " +
                        "ainda precisa de validação de integração antes " +
                        "de ser marcado como executável."
            }

        return PcApplicationCompatibility(
            fileName = cleanName,
            kind = kind,
            displayName = displayName,
            state = state,
            runtimeReady = artifactRuntimeReady,
            applicationValidated = false,
            missingRuntimeStages = missingStages,
            detail = detail,
        )
    }
}
