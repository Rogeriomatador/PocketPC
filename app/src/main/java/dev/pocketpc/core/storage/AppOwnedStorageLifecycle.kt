package dev.pocketpc.core.storage

import android.content.Context
import dev.pocketpc.core.runtime.SafeTreeOps
import java.io.File

data class AppOwnedStorageCleanupReport(
    val removed: List<String>,
    val failed: List<String>,
)

internal enum class AppOwnedStorageRootKind {
    FILES,
    NO_BACKUP,
    CACHE,
}

internal data class AppOwnedStorageMaintenanceTarget(
    val root: AppOwnedStorageRootKind,
    val childName: String,
)

/**
 * Fail-closed allowlist for startup/post-update cleanup.
 *
 * Authentication/session state, WebView data, preferences, databases, HOME,
 * Wine prefixes and user-selected SAF/PocketDrive trees are not maintenance
 * targets. Adding any new deletion target must be an explicit policy change.
 */
internal object AppOwnedStorageMaintenancePolicy {
    val disposableLegacyTargets =
        listOf(
            AppOwnedStorageMaintenanceTarget(
                AppOwnedStorageRootKind.FILES,
                "pocketpc-runtimes",
            ),
            AppOwnedStorageMaintenanceTarget(
                AppOwnedStorageRootKind.FILES,
                "runtime-tools",
            ),
            AppOwnedStorageMaintenanceTarget(
                AppOwnedStorageRootKind.NO_BACKUP,
                "pocketpc-runtimes",
            ),
            AppOwnedStorageMaintenanceTarget(
                AppOwnedStorageRootKind.CACHE,
                "pocketpc-runtimes",
            ),
            AppOwnedStorageMaintenanceTarget(
                AppOwnedStorageRootKind.CACHE,
                "runtime-tools",
            ),
        )

    fun permits(
        root: AppOwnedStorageRootKind,
        childName: String,
    ): Boolean {
        if (
            childName.isBlank() ||
            '/' in childName ||
            '\\' in childName ||
            childName == "." ||
            childName == ".."
        ) {
            return false
        }
        return AppOwnedStorageMaintenanceTarget(
            root = root,
            childName = childName,
        ) in disposableLegacyTargets
    }
}

/**
 * Owns cleanup only for explicitly allowlisted package-private legacy paths.
 *
 * User-selected SAF/PocketDrive trees are deliberately excluded: those are
 * user-owned documents and must never be recursively deleted as application
 * maintenance. Current browser/session data, preferences, databases and
 * runtime/user profiles are also outside the deletion allowlist.
 */
object AppOwnedStorageLifecycle {
    fun runStartupMaintenance(
        context: Context,
    ): AppOwnedStorageCleanupReport {
        val roots =
            mapOf(
                AppOwnedStorageRootKind.FILES to
                    context.filesDir.canonicalFile,
                AppOwnedStorageRootKind.NO_BACKUP to
                    context.noBackupFilesDir.canonicalFile,
                AppOwnedStorageRootKind.CACHE to
                    context.cacheDir.canonicalFile,
            )

        val removed = mutableListOf<String>()
        val failed = mutableListOf<String>()

        AppOwnedStorageMaintenancePolicy
            .disposableLegacyTargets
            .forEach { target ->
                if (
                    !AppOwnedStorageMaintenancePolicy.permits(
                        target.root,
                        target.childName,
                    )
                ) {
                    failed += target.childName
                    return@forEach
                }

                val owner = roots.getValue(target.root)
                val candidate = File(owner, target.childName)
                val canonical =
                    runCatching {
                        candidate.canonicalFile
                    }.getOrNull()
                if (canonical == null) {
                    failed += candidate.path
                    return@forEach
                }
                if (canonical.parentFile != owner) {
                    failed += canonical.path
                    return@forEach
                }
                if (!canonical.exists()) {
                    return@forEach
                }

                val deleted =
                    runCatching {
                        SafeTreeOps.deleteNoFollow(
                            canonical,
                        )
                    }.getOrDefault(false)
                if (deleted && !canonical.exists()) {
                    removed += canonical.path
                } else {
                    failed += canonical.path
                }
            }

        return AppOwnedStorageCleanupReport(
            removed = removed.sorted(),
            failed = failed.sorted(),
        )
    }
}
