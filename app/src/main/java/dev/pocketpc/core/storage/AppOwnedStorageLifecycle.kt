package dev.pocketpc.core.storage

import android.content.Context
import dev.pocketpc.core.runtime.SafeTreeOps
import java.io.File

data class AppOwnedStorageCleanupReport(
    val removed: List<String>,
    val failed: List<String>,
)

/**
 * Owns cleanup only for directories that are private to the PocketPC package.
 *
 * User-selected SAF/PocketDrive trees are deliberately excluded: those are
 * user-owned documents and must never be recursively deleted as application
 * maintenance. Current runtime/system state lives under filesDir,
 * noBackupFilesDir, or cacheDir, which Android removes with the package.
 */
object AppOwnedStorageLifecycle {
    fun runStartupMaintenance(
        context: Context,
    ): AppOwnedStorageCleanupReport {
        val roots =
            listOf(
                context.filesDir.canonicalFile,
                context.noBackupFilesDir.canonicalFile,
                context.cacheDir.canonicalFile,
            )
        val legacy =
            listOf(
                File(context.filesDir, "pocketpc-runtimes"),
                File(context.filesDir, "runtime-tools"),
                File(context.noBackupFilesDir, "pocketpc-runtimes"),
                File(context.cacheDir, "pocketpc-runtimes"),
                File(context.cacheDir, "runtime-tools"),
            )

        val removed = mutableListOf<String>()
        val failed = mutableListOf<String>()

        legacy.forEach { candidate ->
            val canonical =
                runCatching {
                    candidate.canonicalFile
                }.getOrNull()
            if (canonical == null) {
                failed += candidate.path
                return@forEach
            }
            val owner =
                roots.singleOrNull {
                    root ->
                    canonical.parentFile == root
                }
            if (owner == null) {
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
