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

        val kind =
            if (
                cleanName.contains(
                    "roblox",
                    ignoreCase = true,
                )
            ) {
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

        val runtimeReady = readiness.executableReady
        val state =
            if (runtimeReady) {
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
                !runtimeReady &&
                    kind == PcApplicationKind.ROBLOX_DESKTOP ->
                    "O instalador do Roblox foi reconhecido, mas o " +
                        "runtime Windows x64 ainda não está pronto. " +
                        "O PocketPC não tentará iniciar o jogo nem " +
                        "contornar proteções enquanto tradução x86_64, " +
                        "Win32, gráficos, áudio/input e processos não " +
                        "estiverem implementados e validados."

                !runtimeReady ->
                    "O arquivo foi reconhecido como software de PC, " +
                        "mas a execução continua bloqueada até todos " +
                        "os estágios do runtime Windows passarem."

                kind == PcApplicationKind.ROBLOX_DESKTOP ->
                    "O runtime base está pronto, mas Roblox ainda " +
                        "precisa de um teste de integração específico. " +
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
            runtimeReady = runtimeReady,
            applicationValidated = false,
            missingRuntimeStages = missingStages,
            detail = detail,
        )
    }
}
