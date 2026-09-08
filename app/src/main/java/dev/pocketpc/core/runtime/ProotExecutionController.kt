package dev.pocketpc.core.runtime

enum class ProotExecutionState {
    BLOCKED,
    START_FAILED,
    TIMED_OUT,
    EXITED,
}

data class ProotExecutionResult(
    val state: ProotExecutionState,
    val started: Boolean,
    val exitCode: Int?,
    val output: String,
    val outputTruncated: Boolean,
    val blockers: List<String>,
    val error: String?,
) {
    val passed: Boolean
        get() =
            state == ProotExecutionState.EXITED &&
                exitCode == 0
}

class ProotExecutionController(
    private val supervisor: RuntimeProcessSupervisor =
        RuntimeProcessSupervisor(),
) {
    suspend fun executeOneShot(
        plan: ProotInvocationPlan,
        userApproved: Boolean,
        timeoutMillis: Long = 15_000L,
    ): ProotExecutionResult {
        val structuralBlockers =
            plan.blockers.filterNot {
                it ==
                    EXECUTION_APPROVAL_BLOCKER
            }

        if (!userApproved) {
            return ProotExecutionResult(
                state = ProotExecutionState.BLOCKED,
                started = false,
                exitCode = null,
                output = "",
                outputTruncated = false,
                blockers =
                    (
                        structuralBlockers +
                            EXECUTION_APPROVAL_BLOCKER
                    ).distinct(),
                error =
                    "A execução exige aprovação explícita " +
                        "do usuário.",
            )
        }

        if (
            structuralBlockers.isNotEmpty() ||
            plan.argv.isEmpty()
        ) {
            return ProotExecutionResult(
                state = ProotExecutionState.BLOCKED,
                started = false,
                exitCode = null,
                output = "",
                outputTruncated = false,
                blockers =
                    if (
                        structuralBlockers.isNotEmpty()
                    ) {
                        structuralBlockers
                    } else {
                        listOf(
                            "INVOCATION_ARGV_NOT_READY"
                        )
                    },
                error =
                    "O plano PRoot ainda possui gates " +
                        "estruturais bloqueados.",
            )
        }

        val process =
            supervisor.runOneShot(
                ProcessRunSpec(
                    argv = plan.argv,
                    environment = plan.environment,
                    timeoutMillis =
                        timeoutMillis.coerceIn(
                            1_000L,
                            120_000L,
                        ),
                    maxOutputBytes =
                        MAX_CAPTURE_BYTES,
                )
            )

        val state =
            when {
                !process.started ->
                    ProotExecutionState.START_FAILED
                process.timedOut ->
                    ProotExecutionState.TIMED_OUT
                else ->
                    ProotExecutionState.EXITED
            }

        return ProotExecutionResult(
            state = state,
            started = process.started,
            exitCode = process.exitCode,
            output = process.output,
            outputTruncated =
                process.outputTruncated,
            blockers = emptyList(),
            error = process.error,
        )
    }

    fun stopActive(): Boolean =
        supervisor.stopActive()

    companion object {
        const val EXECUTION_APPROVAL_BLOCKER =
            "EXECUTION_REQUIRES_USER_APPROVAL"
        private const val MAX_CAPTURE_BYTES =
            1024 * 1024
    }
}
