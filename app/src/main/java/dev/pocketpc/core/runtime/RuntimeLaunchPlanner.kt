package dev.pocketpc.core.runtime

enum class LaunchBlocker {
    SUBSTRATE_MISSING,
    METADATA_INVALID,
    LINKS_NOT_PREPARED,
    LINKS_VERIFY_FAILED,
    ENTRYPOINT_NOT_RESOLVED,
    ENTRYPOINT_NOT_REGULAR_FILE,
    EXECUTOR_DISABLED,
}

data class RuntimeLaunchAssessment(
    val ready: Boolean,
    val blockers: Set<LaunchBlocker>,
    val requestedEntrypoint: String,
    val resolvedGuestEntrypoint: String?,
    val entrypointDataPath: String?,
    val linkHops: Int,
)

object RuntimeLaunchPlanner {
    fun assess(
        runtime: InstalledRuntime,
        substrate: ExecutionSubstrateStatus,
        substrateExecutionReady: Boolean =
            substrate.prootReady,
    ): RuntimeLaunchAssessment {
        val blockers = linkedSetOf<LaunchBlocker>()
        if (!substrateExecutionReady) {
            blockers +=
                LaunchBlocker.SUBSTRATE_MISSING
        }

        if (runtime.stats.linksRecorded > 0) {
            if (!runtime.linksPrepared) {
                blockers += LaunchBlocker.LINKS_NOT_PREPARED
            } else {
                val linkAudit = RootfsLinkManager().verify(runtime)
                if (!linkAudit.prepared) {
                    blockers += LaunchBlocker.LINKS_VERIFY_FAILED
                }
            }
        }

        val metadata = runCatching {
            RootfsMetadata.read(runtime.metadataFile, runtime.manifest.entryLimit)
        }.getOrElse {
            blockers += LaunchBlocker.METADATA_INVALID
            emptyList()
        }

        val resolution = if (LaunchBlocker.METADATA_INVALID !in blockers) {
            RootfsGuestResolver.resolve(
                rootfs = runtime.rootfsData,
                metadata = metadata,
                requestedAbsolutePath = runtime.manifest.entrypoint,
            )
        } else {
            null
        }

        if (resolution == null || resolution.error != null) {
            blockers += LaunchBlocker.ENTRYPOINT_NOT_RESOLVED
        } else if (!resolution.regularFile) {
            blockers += LaunchBlocker.ENTRYPOINT_NOT_REGULAR_FILE
        }

        // Execution is implemented by ProotExecutionController, but it remains
        // disabled until an invocation plan passes and the user explicitly
        // approves the one-shot attempt.
        blockers += LaunchBlocker.EXECUTOR_DISABLED

        return RuntimeLaunchAssessment(
            ready = blockers.isEmpty(),
            blockers = blockers,
            requestedEntrypoint = runtime.manifest.entrypoint,
            resolvedGuestEntrypoint = resolution?.resolvedGuestPath,
            entrypointDataPath = resolution?.hostPath?.toString(),
            linkHops = resolution?.linkHops ?: 0,
        )
    }
}
