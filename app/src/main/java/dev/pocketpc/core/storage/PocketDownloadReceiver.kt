package dev.pocketpc.core.storage

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        val ids = readIds().toMutableSet()
        ids += downloadId
        writeIds(ids)
    }

    fun contains(downloadId: Long): Boolean =
        downloadId in readIds()

    fun remove(downloadId: Long) {
        val ids = readIds().toMutableSet()
        if (ids.remove(downloadId)) {
            writeIds(ids)
        }
    }

    private fun readIds(): Set<Long> =
        prefs.getStringSet(KEY_IDS, emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()

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

class PocketDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (
            intent.action !=
            DownloadManager.ACTION_DOWNLOAD_COMPLETE
        ) {
            return
        }

        val downloadId =
            intent.getLongExtra(
                DownloadManager.EXTRA_DOWNLOAD_ID,
                -1L,
            )
        if (downloadId < 0L) return

        val appContext = context.applicationContext
        val registry =
            PocketDownloadRegistry(appContext)
        if (!registry.contains(downloadId)) {
            return
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                importCompletedDownload(
                    context = appContext,
                    downloadId = downloadId,
                    registry = registry,
                )
            } finally {
                pending.finish()
            }
        }
    }
}

private suspend fun importCompletedDownload(
    context: Context,
    downloadId: Long,
    registry: PocketDownloadRegistry,
) {
    val manager =
        context.getSystemService(
            Context.DOWNLOAD_SERVICE
        ) as DownloadManager
    val query =
        DownloadManager.Query()
            .setFilterById(downloadId)

    var title = "download-$downloadId"
    var mimeType: String? = null
    var successful = false

    manager.query(query)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val statusIndex =
                cursor.getColumnIndex(
                    DownloadManager.COLUMN_STATUS
                )
            val titleIndex =
                cursor.getColumnIndex(
                    DownloadManager.COLUMN_TITLE
                )
            val mimeIndex =
                cursor.getColumnIndex(
                    DownloadManager.COLUMN_MEDIA_TYPE
                )

            successful =
                statusIndex >= 0 &&
                    cursor.getInt(statusIndex) ==
                    DownloadManager.STATUS_SUCCESSFUL

            if (titleIndex >= 0) {
                title =
                    cursor.getString(titleIndex)
                        ?.takeIf { it.isNotBlank() }
                        ?: title
            }
            if (mimeIndex >= 0) {
                mimeType = cursor.getString(mimeIndex)
            }
        }
    }

    if (!successful) {
        return
    }

    val storage = StorageRepository(context)
    if (storage.rootUriString == null) {
        registry.remove(downloadId)
        return
    }

    val descriptor =
        runCatching {
            manager.openDownloadedFile(downloadId)
        }.getOrNull()
            ?: return

    descriptor.use { parcel ->
        FileInputStream(parcel.fileDescriptor).use { input ->
            storage.importIntoPocketDrive(
                directory = PocketDriveDirectory.DOWNLOADS,
                fileName = title,
                mimeType = mimeType,
                source = input,
            ).getOrThrow()
        }
    }

    manager.remove(downloadId)
    registry.remove(downloadId)
}
