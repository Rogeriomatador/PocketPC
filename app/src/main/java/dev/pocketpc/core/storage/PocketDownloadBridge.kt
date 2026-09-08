package dev.pocketpc.core.storage

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PocketDownloadRegistry(
    context: Context,
) {
    private val prefs =
        context.getSharedPreferences(
            "pocketpc-download-registry",
            Context.MODE_PRIVATE,
        )

    fun register(downloadId: Long): Unit = synchronized(registryLock) {
        val ids = ids().toMutableSet()
        ids += downloadId
        writeIds(ids)
    }

    fun ids(): Set<Long> =
        prefs.getStringSet(KEY_IDS, emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    fun remove(downloadId: Long): Unit = synchronized(registryLock) {
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
        val registryLock = Any()
    }
}

class PocketDownloadReceiver :
    BroadcastReceiver() {
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

        val appContext =
            context.applicationContext
        val registry =
            PocketDownloadRegistry(appContext)
        if (downloadId !in registry.ids()) {
            return
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO)
            .launch {
                try {
                    PocketDownloadImporter
                        .importReady(
                            context = appContext,
                            storage =
                                StorageRepository(
                                    appContext
                                ),
                        )
                } finally {
                    pending.finish()
                }
            }
    }
}

object PocketDownloadImporter {
    private val importMutex = Mutex()
    suspend fun importReady(
        context: Context,
        storage: StorageRepository,
    ): Int =
        withContext(Dispatchers.IO) {
            importMutex.withLock {
            if (storage.rootUriString == null) {
                return@withLock 0
            }

            val registry =
                PocketDownloadRegistry(context)
            val manager =
                context.getSystemService(
                    Context.DOWNLOAD_SERVICE
                ) as DownloadManager

            var imported = 0

            registry.ids().forEach { downloadId ->
                try {
                val metadata =
                    queryDownload(
                        manager,
                        downloadId,
                    )

                if (metadata == null) {
                    // The Android DownloadManager entry was removed outside
                    // PocketPC. Do not keep an unreachable ID forever.
                    registry.remove(downloadId)
                    return@forEach
                }

                when (metadata.status) {
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
                            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
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

                        result.onSuccess { entry ->
                            PocketPcPackageRegistry(
                                context
                            ).record(entry)
                            registry.remove(downloadId)
                            imported++
                            // Copy succeeded; failed staging cleanup must not import it again.
                            runCatching { manager.remove(downloadId) }
                                .onFailure { Log.w("PocketDownload", "Staging cleanup failed for $downloadId", it) }
                        }
                    }

                    else -> Unit
                }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // Retain the source when a provider is temporarily unavailable.
                    Log.w("PocketDownload", "Import deferred for $downloadId", failure)
                }
            }

            imported
            }
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

    checkNotNull(manager.query(query)) { "DownloadManager query unavailable" }.use { cursor ->
        if (!cursor.moveToFirst()) {
            return null
        }

        val statusIndex =
            cursor.getColumnIndex(
                DownloadManager.COLUMN_STATUS
            )
        check(statusIndex >= 0) { "DownloadManager status unavailable" }

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
