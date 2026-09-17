package dev.pocketpc.core.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local handoff between storage/download surfaces and the desktop
 * file-open UI. It never launches Android activities itself.
 */
data class PocketFileOpenRequest(
    val plan: PocketFileOpenPlan,
    val uri: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long = 0L,
)

object PocketFileOpenCoordinator {
    private val mutablePlan =
        MutableStateFlow<PocketFileOpenPlan?>(null)
    private val mutableRequest =
        MutableStateFlow<PocketFileOpenRequest?>(null)

    /**
     * Kept as a lightweight compatibility/read-only signal for callers that
     * care only about the routing decision.
     */
    val plan: StateFlow<PocketFileOpenPlan?> =
        mutablePlan.asStateFlow()

    /** Full request used by PocketPC handlers that need the actual file. */
    val request: StateFlow<PocketFileOpenRequest?> =
        mutableRequest.asStateFlow()

    fun present(
        name: String,
        mimeType: String? = null,
        uri: String? = null,
        sizeBytes: Long = 0L,
    ): PocketFileOpenRequest {
        val nextPlan =
            planPocketFileOpen(
                name = name,
                mimeType = mimeType,
            )
        return present(
            plan = nextPlan,
            uri = uri,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
        )
    }

    fun present(plan: PocketFileOpenPlan) {
        present(
            plan = plan,
            uri = null,
            mimeType = null,
            sizeBytes = 0L,
        )
    }

    fun present(
        plan: PocketFileOpenPlan,
        uri: String?,
        mimeType: String?,
        sizeBytes: Long,
    ): PocketFileOpenRequest {
        val next =
            PocketFileOpenRequest(
                plan = plan,
                uri = uri,
                mimeType = mimeType,
                sizeBytes = sizeBytes.coerceAtLeast(0L),
            )
        mutablePlan.value = plan
        mutableRequest.value = next
        return next
    }

    fun dismiss() {
        mutableRequest.value = null
        mutablePlan.value = null
    }
}
