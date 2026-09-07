package dev.pocketpc.core.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

internal const val UPDATE_PREFS_NAME =
    "pocketpc-updater"
internal const val KEY_INSTALL_ATTEMPT_DOWNLOAD_ID =
    "install-attempt-download-id"
internal const val EXTRA_UPDATE_DOWNLOAD_ID =
    "dev.pocketpc.core.extra.UPDATE_DOWNLOAD_ID"

data class PocketPcInstallStatus(
    val status: Int,
    val message: String,
    val sessionId: Int,
    val updatedAtMillis: Long,
)

class PocketPcInstallStatusStore(
    context: Context,
) {
    private val prefs =
        context.applicationContext
            .getSharedPreferences(
                UPDATE_PREFS_NAME,
                Context.MODE_PRIVATE,
            )

    fun save(
        status: Int,
        message: String,
        sessionId: Int,
    ) {
        prefs.edit()
            .putInt(KEY_STATUS, status)
            .putString(KEY_MESSAGE, message)
            .putInt(KEY_SESSION_ID, sessionId)
            .putLong(
                KEY_UPDATED_AT,
                System.currentTimeMillis(),
            )
            .apply()
    }

    fun read(): PocketPcInstallStatus? {
        if (!prefs.contains(KEY_STATUS)) {
            return null
        }
        return PocketPcInstallStatus(
            status = prefs.getInt(
                KEY_STATUS,
                PackageInstaller.STATUS_FAILURE,
            ),
            message =
                prefs.getString(
                    KEY_MESSAGE,
                    "",
                ).orEmpty(),
            sessionId =
                prefs.getInt(
                    KEY_SESSION_ID,
                    -1,
                ),
            updatedAtMillis =
                prefs.getLong(
                    KEY_UPDATED_AT,
                    0L,
                ),
        )
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_STATUS)
            .remove(KEY_MESSAGE)
            .remove(KEY_SESSION_ID)
            .remove(KEY_UPDATED_AT)
            .apply()
    }

    private companion object {
        const val KEY_STATUS =
            "install-status"
        const val KEY_MESSAGE =
            "install-status-message"
        const val KEY_SESSION_ID =
            "install-session-id"
        const val KEY_UPDATED_AT =
            "install-status-updated-at"
    }
}

class PocketPcInstallReceiver :
    BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val status =
            intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE,
            )
        val sessionId =
            intent.getIntExtra(
                PackageInstaller.EXTRA_SESSION_ID,
                -1,
            )
        val message =
            intent.getStringExtra(
                PackageInstaller.EXTRA_STATUS_MESSAGE
            ).orEmpty()

        PocketPcInstallStatusStore(context)
            .save(
                status = status,
                message = message,
                sessionId = sessionId,
            )

        val downloadId =
            intent.getLongExtra(
                EXTRA_UPDATE_DOWNLOAD_ID,
                -1L,
            )

        if (
            status !=
                PackageInstaller.STATUS_PENDING_USER_ACTION &&
            status !=
                PackageInstaller.STATUS_SUCCESS &&
            downloadId >= 0L
        ) {
            context.applicationContext
                .getSharedPreferences(
                    UPDATE_PREFS_NAME,
                    Context.MODE_PRIVATE,
                )
                .edit()
                .remove(
                    KEY_INSTALL_ATTEMPT_DOWNLOAD_ID
                )
                .apply()
        }

        if (
            status ==
            PackageInstaller.STATUS_PENDING_USER_ACTION
        ) {
            val confirmation =
                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.TIRAMISU
                ) {
                    intent.getParcelableExtra(
                        Intent.EXTRA_INTENT,
                        Intent::class.java,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(
                        Intent.EXTRA_INTENT
                    )
                }

            confirmation
                ?.apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }
                ?.let { userAction ->
                    postUserActionNotification(
                        context = context,
                        confirmation = userAction,
                        sessionId = sessionId,
                    )

                    runCatching {
                        context.startActivity(
                            userAction
                        )
                    }.onFailure {
                        if (downloadId >= 0L) {
                            context.applicationContext
                                .getSharedPreferences(
                                    UPDATE_PREFS_NAME,
                                    Context.MODE_PRIVATE,
                                )
                                .edit()
                                .remove(
                                    KEY_INSTALL_ATTEMPT_DOWNLOAD_ID
                                )
                                .apply()
                        }
                    }
                }
        }
    }
}

private const val UPDATE_NOTIFICATION_CHANNEL =
    "pocketpc-updates"
private const val UPDATE_NOTIFICATION_ID = 2101

private fun postUserActionNotification(
    context: Context,
    confirmation: Intent,
    sessionId: Int,
) {
    val manager =
        context.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

    manager.createNotificationChannel(
        NotificationChannel(
            UPDATE_NOTIFICATION_CHANNEL,
            "PocketPC Updates",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description =
                "Confirmacoes exigidas pelo Android para atualizar o PocketPC."
        }
    )

    val flags =
        PendingIntent.FLAG_UPDATE_CURRENT or
            PendingIntent.FLAG_IMMUTABLE

    val action =
        PendingIntent.getActivity(
            context,
            sessionId.coerceAtLeast(0),
            confirmation,
            flags,
        )

    val notification =
        Notification.Builder(
            context,
            UPDATE_NOTIFICATION_CHANNEL,
        )
            .setSmallIcon(
                android.R.drawable.stat_sys_download_done
            )
            .setContentTitle(
                "Atualizacao do PocketPC pronta"
            )
            .setContentText(
                "Toque para confirmar a instalacao exigida pelo Android."
            )
            .setCategory(Notification.CATEGORY_SYSTEM)
            .setAutoCancel(true)
            .setContentIntent(action)
            .build()

    runCatching {
        manager.notify(
            UPDATE_NOTIFICATION_ID,
            notification,
        )
    }
}
