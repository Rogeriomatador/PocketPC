package dev.pocketpc.core.runtime

data class PcApplicationTarget(
    val uri: String,
    val fileName: String,
    val sizeBytes: Long,
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
        val launchEligible =
            readiness.executableReady &&
                compatibility.runtimeReady &&
                compatibility.applicationValidated

        val firstPending =
            gates.firstOrNull {
                it.state !=
                    PcRuntimeExecutionGateState.READY
            }

        return PcRuntimeExecutionPlan(
            target = target,
            gates = gates,
            launchEligible = launchEligible,
            nextAction =
                if (firstPending == null) {
                    "Todos os gates estão READY. O próximo passo " +
                        "é uma tentativa de execução registrada."
                } else {
                    "Próximo gate: ${firstPending.label}. " +
                        firstPending.detail
                },
        )
    }
}
