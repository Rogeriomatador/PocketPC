package dev.pocketpc.core.runtime

enum class PcApplicationGraphicsProfile {
    WINDOWED_GDI,
    D3D_DXVK,
    ;

    companion object {
        /**
         * Keep arbitrary Windows targets fail-closed behind the full D3D/DXVK
         * path. Only the explicitly staged WinRAR validation target may use the
         * earlier GDI/window-driver path before Vulkan WSI is production-ready.
         */
        fun forFileName(fileName: String): PcApplicationGraphicsProfile {
            val normalized =
                fileName.trim().lowercase()
            return if (
                normalized.startsWith("winrar") &&
                normalized.endsWith(".exe")
            ) {
                WINDOWED_GDI
            } else {
                D3D_DXVK
            }
        }
    }
}

data class PcApplicationTarget(
    val uri: String,
    val fileName: String,
    val sizeBytes: Long,
    val graphicsProfile: PcApplicationGraphicsProfile =
        PcApplicationGraphicsProfile.forFileName(fileName),
)

enum class PcRuntimeExecutionGateState {
    READY,
    BLOCKED,
    UNVALIDATED,
}

data class PcRuntimeExecutionGate(
    val id: String,
    val label: String,
    val state: PcRuntimeExecutionGateState,
    val detail: String,
)

data class PcRuntimeExecutionPlan(
    val target: PcApplicationTarget,
    val gates: List<PcRuntimeExecutionGate>,
    val launchEligible: Boolean,
    val nextAction: String,
    val attemptEligible: Boolean = false,
)

object PcRuntimeExecutionPlanner {
    fun build(
        target: PcApplicationTarget,
        readiness: PcRuntimeReadiness,
        compatibility: PcApplicationCompatibility,
    ): PcRuntimeExecutionPlan {
        val runtimeGates =
            readiness.stages.map { stage ->
                PcRuntimeExecutionGate(
                    id = stage.id,
                    label = stage.label,
                    state =
                        if (
                            stage.state ==
                            PcRuntimeStageState.READY
                        ) {
                            PcRuntimeExecutionGateState.READY
                        } else {
                            PcRuntimeExecutionGateState.BLOCKED
                        },
                    detail = stage.detail,
                )
            }

        val applicationGate =
            PcRuntimeExecutionGate(
                id = "application-integration",
                label =
                    compatibility.displayName +
                        " — integração",
                state =
                    if (
                        compatibility.applicationValidated
                    ) {
                        PcRuntimeExecutionGateState.READY
                    } else {
                        PcRuntimeExecutionGateState.UNVALIDATED
                    },
                detail =
                    if (
                        compatibility.applicationValidated
                    ) {
                        "Aplicativo validado neste runtime."
                    } else {
                        "Ainda falta teste de integração real do " +
                            "aplicativo sobre o runtime completo."
                    },
            )

        val gates =
            runtimeGates + applicationGate
        /*
         * An application cannot become integration-validated before its first
         * controlled run. GDI validation targets only need the windowed Wine
         * path; arbitrary/D3D targets retain the stricter graphics gate.
         */
        val attemptEligible =
            when (target.graphicsProfile) {
                PcApplicationGraphicsProfile.WINDOWED_GDI ->
                    readiness.windowedAttemptReady
                PcApplicationGraphicsProfile.D3D_DXVK ->
                    readiness.graphicsAttemptReady
            }
        val launchEligible =
            attemptEligible &&
                compatibility.applicationValidated

        val firstPending =
            gates.firstOrNull {
                it.state !=
                    PcRuntimeExecutionGateState.READY &&
                    !(
                        target.graphicsProfile ==
                            PcApplicationGraphicsProfile.WINDOWED_GDI &&
                            it.id == "graphics-bridge"
                        )
            }

        return PcRuntimeExecutionPlan(
            target = target,
            gates = gates,
            attemptEligible =
                attemptEligible,
            launchEligible = launchEligible,
            nextAction =
                when {
                    attemptEligible &&
                        !compatibility
                            .applicationValidated ->
                        "Runtime base READY para uma tentativa " +
                            "controlada e registrada. A integração " +
                            "do aplicativo continua UNVALIDATED " +
                            "até existir evidência real."

                    firstPending == null ->
                        "Todos os gates exigidos para este perfil estão READY. " +
                            "O próximo passo é uma execução validada registrada."

                    else ->
                        "Próximo gate: ${firstPending.label}. " +
                            firstPending.detail
                },
        )
    }
}
