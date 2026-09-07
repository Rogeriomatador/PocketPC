package dev.pocketpc.core.update

import android.app.DownloadManager
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class PocketPcUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(
    appContext,
    workerParams,
) {
    override suspend fun doWork(): Result {
        val updater =
            PocketPcUpdater(applicationContext)

        val pending =
            updater.queryPendingDownload()

        if (
            pending?.status ==
            DownloadManager.STATUS_SUCCESSFUL
        ) {
            val verified =
                updater.verifyPendingDownload()
                    .getOrNull()

            if (
                verified != null &&
                shouldAutoInstallUpdate(
                    enabled =
                        updater
                            .autoInstallVerifiedEnabled(),
                    verified = true,
                    canInstallPackages =
                        updater
                            .canRequestPackageInstalls(),
                    alreadyAttempted =
                        updater
                            .installAttemptedForPending(),
                )
            ) {
                updater.requestInstall(verified)
            }

            return Result.success()
        }

        if (
            pending != null &&
            pending.status !=
                DownloadManager.STATUS_FAILED
        ) {
            return Result.success()
        }

        if (!updater.autoCheckEnabled()) {
            return Result.success()
        }

        if (!updater.shouldRunAutomaticCheck()) {
            return Result.success()
        }

        updater.markAutomaticCheck()

        val check =
            updater.checkForUpdate()
                .getOrElse {
                    return Result.retry()
                }

        updater.rememberManifest(
            check.manifest
        )

        if (!check.updateAvailable) {
            return Result.success()
        }

        val canAutoDownload =
            updater
                .autoDownloadUnmeteredEnabled() &&
                updater.isUnmeteredNetwork()

        if (!canAutoDownload) {
            return Result.success()
        }

        return updater
            .beginDownload(check.manifest)
            .fold(
                onSuccess = {
                    Result.success()
                },
                onFailure = {
                    Result.retry()
                },
            )
    }
}

object PocketPcUpdateScheduler {
    private const val UNIQUE_WORK =
        "pocketpc-periodic-update"

    fun schedule(context: Context) {
        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(
                    NetworkType.CONNECTED
                )
                .build()

        val request =
            PeriodicWorkRequestBuilder<
                PocketPcUpdateWorker
            >(
                6,
                TimeUnit.HOURS,
                1,
                TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .build()

        WorkManager.getInstance(
            context.applicationContext
        ).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(
            context.applicationContext
        ).cancelUniqueWork(UNIQUE_WORK)
    }
}
