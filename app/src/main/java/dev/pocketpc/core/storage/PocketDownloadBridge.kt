package dev.pocketpc.core.storage

import android.app.DownloadManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream

class PocketDownloadRegistry(
    context: Context,
) {
    private val prefs =
        context.getSharedPreferences(
            "pocketpc-download-registry",
            Context.MODE_PRIVATE,
        )

    fun register(downloadId: Long) {
        val ids = ids().toMutableSet()
        ids += downloadId
        writeIds(ids)
    }

    fun ids(): Set<Long> =
        prefs.getStringSet(KEY_IDS, emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    fun remove(downloadId: Long) {
        val ids = ids().toMutableSet()
        if (ids.remove(downloadId)) {
            writeIds(ids)
        }
    }

    private fun writeIds(ids: Set<Long>) {
        prefs.edit()
            .putStringSet(
                KEY_IDS,
                ids.map(Long::toString).toSet(),
            )
            .apply()
    }

    private companion object {
        const val KEY_IDS = "download-ids"
    }
}

object PocketDownloadImporter {
    suspend fun importReady(
        context: Context,
        storage: StorageRepository,
    ): Int =
        withContext(Dispatchers.IO) {
            if (storage.rootUriString == null) {
                return@withContext 0
            }

            val registry =
                PocketDownloadRegistry(context)
            val manager =
                context.getSystemService(
                    Context.DOWNLOAD_SERVICE
                ) as DownloadManager

            var imported = 0

            registry.ids().forEach { downloadId ->
                val metadata =
                    queryDownload(
                        manager,
                        downloadId,
                    )

                when (metadata?.status) {
                    DownloadManager.STATUS_FAILED -> {
                        registry.remove(downloadId)
                    }

                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val descriptor =
                            runCatching {
                                manager.openDownloadedFile(
                                    downloadId
                                )
                            }.getOrNull()
                                ?: return@forEach

                        val result =
                            descriptor.use { parcel ->
                                FileInputStream(
                                    parcel.fileDescriptor
                                ).use { input ->
                                    storage.importIntoPocketDrive(
                                        directory =
                                            PocketDriveDirectory.DOWNLOADS,
                                        fileName =
                                            metadata.title,
                                        mimeType =
                                            metadata.mimeType,
                                        source = input,
                                    )
                                }
                            }

                        result.onSuccess {
                            manager.remove(downloadId)
                            registry.remove(downloadId)
                            imported++
                        }
                    }

                    else -> Unit
                }
            }

            imported
        }
}

private data class RegisteredDownload(
    val title: String,
    val mimeType: String?,
    val status: Int,
)

private fun queryDownload(
    manager: DownloadManager,
    downloadId: Long,
): RegisteredDownload? {
    val query =
        DownloadManager.Query()
            .setFilterById(downloadId)

    manager.query(query)?.use { cursor ->
        if (!cursor.moveToFirst()) {
            return null
        }

        val statusIndex =
            cursor.getColumnIndex(
                DownloadManager.COLUMN_STATUS
            )
        if (statusIndex < 0) return null

        val titleIndex =
            cursor.getColumnIndex(
                DownloadManager.COLUMN_TITLE
            )
        val mimeIndex =
            cursor.getColumnIndex(
                DownloadManager.COLUMN_MEDIA_TYPE
            )

        return RegisteredDownload(
            title =
                if (titleIndex >= 0) {
                    cursor.getString(titleIndex)
                        ?.takeIf { it.isNotBlank() }
                        ?: "download-$downloadId"
                } else {
                    "download-$downloadId"
                },
            mimeType =
                if (mimeIndex >= 0) {
                    cursor.getString(mimeIndex)
                } else {
                    null
                },
            status =
                cursor.getInt(statusIndex),
        )
    }

    return null
}
