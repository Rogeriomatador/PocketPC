package dev.pocketpc.core.runtime

import java.io.File

data class VersionedPruneResult(
    val removedVersions: List<String>,
    val failedVersions: List<String>,
)

/**
 * Removes superseded version directories for one component without following
 * links and without ever deleting outside the requested component directory.
 * Hidden transaction/backup directories are intentionally left to the
 * component-specific transaction recovery code.
 */
object VersionedInstallPruner {
    fun prune(
        containerRoot: File,
        componentId: String,
        keepVersion: String,
    ): VersionedPruneResult {
        requireSafeSegment(componentId, "componentId")
        requireSafeSegment(keepVersion, "keepVersion")

        val root = containerRoot.canonicalFile
        if (!root.exists()) {
            return VersionedPruneResult(
                removedVersions = emptyList(),
                failedVersions = emptyList(),
            )
        }
        require(root.isDirectory) {
            "VERSIONED_PRUNE_ROOT_NOT_DIRECTORY"
        }

        val componentDirectory =
            File(root, componentId).canonicalFile
        require(
            componentDirectory.parentFile == root,
        ) {
            "VERSIONED_PRUNE_COMPONENT_ESCAPED_ROOT"
        }
        if (!componentDirectory.exists()) {
            return VersionedPruneResult(
                removedVersions = emptyList(),
                failedVersions = emptyList(),
            )
        }
        require(
            SafeTreeOps.isPlainDirectory(
                componentDirectory.toPath(),
            ),
        ) {
            "VERSIONED_PRUNE_COMPONENT_NOT_PLAIN_DIRECTORY"
        }

        val keep =
            File(
                componentDirectory,
                keepVersion,
            ).canonicalFile
        require(keep.parentFile == componentDirectory) {
            "VERSIONED_PRUNE_KEEP_ESCAPED_COMPONENT"
        }

        val removed = mutableListOf<String>()
        val failed = mutableListOf<String>()

        componentDirectory.listFiles()
            .orEmpty()
            .filter {
                !it.name.startsWith(".") &&
                    SafeTreeOps.isPlainDirectory(
                        it.toPath(),
                    )
            }
            .forEach { candidate ->
                val canonical =
                    runCatching {
                        candidate.canonicalFile
                    }.getOrNull()
                if (
                    canonical == null ||
                    canonical.parentFile !=
                    componentDirectory
                ) {
                    failed += candidate.name
                    return@forEach
                }
                if (canonical == keep) {
                    return@forEach
                }

                val deleted =
                    runCatching {
                        SafeTreeOps.deleteNoFollow(
                            canonical,
                        )
                    }.getOrDefault(false)
                if (deleted && !canonical.exists()) {
                    removed += candidate.name
                } else {
                    failed += candidate.name
                }
            }

        return VersionedPruneResult(
            removedVersions =
                removed.sorted(),
            failedVersions =
                failed.sorted(),
        )
    }

    private fun requireSafeSegment(
        value: String,
        label: String,
    ) {
        require(
            value.isNotBlank() &&
                value != "." &&
                value != ".." &&
                '/' !in value &&
                '\\' !in value &&
                '\u0000' !in value,
        ) {
            "VERSIONED_PRUNE_UNSAFE_SEGMENT:$label"
        }
    }
}
