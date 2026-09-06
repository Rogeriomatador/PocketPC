package dev.pocketpc.core.runtime

import java.io.File

enum class LaunchBlocker {
    SUBSTRATE_MISSING,
    ENTRYPOINT_NOT_EXTRACTED,
    LINK_SEMANTICS_PENDING,
    EXECUTOR_NOT_IMPLEMENTED,
}

data class RuntimeLaunchAssessment(
    val ready: Boolean,
    val blockers: Set<LaunchBlocker>,
    val entrypointDataPath: String,
)

object RuntimeLaunchPlanner {
    fun assess(
        runtime: InstalledRuntime,
        substrate: ExecutionSubstrateStatus,
    ): RuntimeLaunchAssessment {
        val blockers = linkedSetOf<LaunchBlocker>()
        if (!substrate.prootReady) blockers += LaunchBlocker.SUBSTRATE_MISSING

        val entrypointRelative = runtime.manifest.entrypoint.removePrefix("/")
        val entrypointFile = File(runtime.rootfsData, entrypointRelative)
        if (!entrypointFile.isFile) blockers += LaunchBlocker.ENTRYPOINT_NOT_EXTRACTED

        if (runtime.stats.linksRecorded > 0) {
            blockers += LaunchBlocker.LINK_SEMANTICS_PENDING
        }

        // Alpha 5 deliberately has no PRoot invocation/executor yet.
        blockers += LaunchBlocker.EXECUTOR_NOT_IMPLEMENTED

        return RuntimeLaunchAssessment(
            ready = blockers.isEmpty(),
            blockers = blockers,
            entrypointDataPath = entrypointFile.path,
        )
    }
}
