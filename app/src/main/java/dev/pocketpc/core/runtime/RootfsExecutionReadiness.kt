package dev.pocketpc.core.runtime

data class RootfsExecutionReadiness(
    val ready: Boolean,
    val entrypointResolved: Boolean,
    val linksPrepared: Boolean,
    val errors: List<String>,
)

object RootfsExecutionReadinessProbe {
    fun assess(runtime: InstalledRuntime): RootfsExecutionReadiness {
        val errors = mutableListOf<String>()

        val linksReady =
            runtime.stats.linksRecorded == 0 || runtime.linksPrepared
        if (!linksReady) {
            errors += "ROOTFS_LINKS_NOT_PREPARED"
        }

        val metadata =
            runCatching {
                RootfsMetadata.read(
                    runtime.metadataFile,
                    runtime.manifest.entryLimit,
                )
            }.getOrElse { error ->
                errors +=
                    "ROOTFS_METADATA_INVALID:" +
                        (error.message ?: error.javaClass.simpleName)
                emptyList()
            }

        val resolution =
            if (metadata.isNotEmpty()) {
                RootfsGuestResolver.resolve(
                    runtime.rootfsData,
                    metadata,
                    runtime.manifest.entrypoint,
                )
            } else {
                null
            }

        val entrypointResolved =
            resolution?.error == null &&
                resolution?.regularFile == true

        if (!entrypointResolved) {
            errors +=
                "ROOTFS_ENTRYPOINT_UNAVAILABLE:" +
                    (resolution?.error ?: runtime.manifest.entrypoint)
        }

        return RootfsExecutionReadiness(
            ready = linksReady && entrypointResolved && errors.isEmpty(),
            entrypointResolved = entrypointResolved,
            linksPrepared = linksReady,
            errors = errors.distinct(),
        )
    }
}
