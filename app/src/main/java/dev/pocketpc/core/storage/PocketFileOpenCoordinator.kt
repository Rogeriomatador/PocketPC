package dev.pocketpc.core.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local handoff between storage/download surfaces and the desktop
 * file-open UI. It never launches Android activities itself.
 */
object PocketFileOpenCoordinator {
    private val mutablePlan =
        MutableStateFlow<PocketFileOpenPlan?>(null)

    val plan: StateFlow<PocketFileOpenPlan?> =
        mutablePlan.asStateFlow()

    fun present(
        name: String,
        mimeType: String? = null,
    ): PocketFileOpenPlan {
        val next =
            planPocketFileOpen(
                name = name,
                mimeType = mimeType,
            )
        mutablePlan.value = next
        return next
    }

    fun present(plan: PocketFileOpenPlan) {
        mutablePlan.value = plan
    }

    fun dismiss() {
        mutablePlan.value = null
    }
}
