package dev.pocketpc.core.performance

enum class GovernorAction {
    HOLD,
    WATCH,
    REDUCE_LOAD,
    REDUCE_AGGRESSIVELY,
}

data class GovernorInput(
    val thermalHeadroom: Float?,
    val lowMemory: Boolean,
)

data class GovernorDecision(
    val action: GovernorAction,
    val reason: String,
    /** Advisory ceiling only. No renderer quality is changed by Alpha 3. */
    val suggestedQualityCeiling: Float,
)

/**
 * Advisory policy for the future performance engine.
 * It does not alter clocks, render resolution, frame rate, or third-party apps.
 */
object PerformanceGovernor {
    fun decide(input: GovernorInput): GovernorDecision {
        if (input.lowMemory) {
            return GovernorDecision(
                action = GovernorAction.REDUCE_LOAD,
                reason = "Android reports low-memory pressure.",
                suggestedQualityCeiling = 0.80f,
            )
        }

        val headroom = input.thermalHeadroom
            ?: return GovernorDecision(
                action = GovernorAction.HOLD,
                reason = "Thermal headroom unavailable; no automatic thermal assumption.",
                suggestedQualityCeiling = 1.00f,
            )

        return when {
            headroom > 1.00f -> GovernorDecision(
                GovernorAction.REDUCE_AGGRESSIVELY,
                "Thermal headroom is above the severe-throttling reference.",
                0.70f,
            )
            headroom >= 0.95f -> GovernorDecision(
                GovernorAction.REDUCE_LOAD,
                "Thermal headroom is in the high-risk range.",
                0.80f,
            )
            headroom >= 0.85f -> GovernorDecision(
                GovernorAction.WATCH,
                "Thermal headroom is approaching a throttling-sensitive range.",
                0.90f,
            )
            else -> GovernorDecision(
                GovernorAction.HOLD,
                "No thermal reduction is currently recommended.",
                1.00f,
            )
        }
    }
}
