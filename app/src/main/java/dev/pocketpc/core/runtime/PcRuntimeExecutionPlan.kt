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
    val attemptEligible: Boolean,
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
        /*
         * An application cannot become integration-validated before its
         * first controlled run. Keep "attempt eligible" separate from
         * "validated launch" so the validation gate does not deadlock.
         */
        val attemptEligible =
            readiness.controlledAttemptReady
        val launchEligible =
            attemptEligible &&
                compatibility.applicationValidated

        val firstPending =
            gates.firstOrNull {
                it.state !=
                    PcRuntimeExecutionGateState.READY
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
                        "Todos os gates estão READY. O próximo passo " +
                            "é uma execução validada registrada."

                    else ->
                        "Próximo gate: ${firstPending.label}. " +
                            firstPending.detail
                },
        )
    }
}
