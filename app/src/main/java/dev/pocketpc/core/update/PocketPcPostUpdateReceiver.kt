package dev.pocketpc.core.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

private const val REOPEN_AFTER_UPDATE_KEY =
    "reopen-after-foreground-update"
private const val UPDATE_NOTIFICATION_CHANNEL =
    "pocketpc-updates"
private const val POST_UPDATE_NOTIFICATION_ID = 2102

internal fun requestPocketPcReopenAfterUpdate(
    context: Context,
) {
    context.applicationContext
        .getSharedPreferences(
            UPDATE_PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        .edit()
        .putBoolean(
            REOPEN_AFTER_UPDATE_KEY,
            true,
        )
        .apply()
}

internal fun cancelPocketPcReopenAfterUpdate(
    context: Context,
) {
    context.applicationContext
        .getSharedPreferences(
            UPDATE_PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        .edit()
        .remove(REOPEN_AFTER_UPDATE_KEY)
        .apply()
}

internal fun clearPostUpdateNotification(
    context: Context,
) {
    val manager =
        context.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager
    manager.cancel(POST_UPDATE_NOTIFICATION_ID)
}

class PocketPcPostUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (
            intent.action !=
            Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val prefs =
            context.applicationContext
                .getSharedPreferences(
                    UPDATE_PREFS_NAME,
                    Context.MODE_PRIVATE,
                )
        if (
            !prefs.getBoolean(
                REOPEN_AFTER_UPDATE_KEY,
                false,
            )
        ) {
            return
        }

        prefs.edit()
            .remove(REOPEN_AFTER_UPDATE_KEY)
            .apply()

        val launchIntent =
            context.packageManager
                .getLaunchIntentForPackage(
                    context.packageName
                )
                ?.apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }

        if (launchIntent != null) {
            // Publish the fallback before asking Android to reopen PocketPC.
            // MainActivity clears it immediately on a successful reopen. This
            // avoids the previous race where the Activity could clear first
            // and the receiver would post a stale notification afterwards.
            postOpenPocketPcFallback(
                context = context,
                launchIntent = launchIntent,
            )

            runCatching {
                context.startActivity(launchIntent)
            }
        }
    }
}

private fun postOpenPocketPcFallback(
    context: Context,
    launchIntent: Intent,
) {
    val manager =
        context.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

    manager.createNotificationChannel(
        NotificationChannel(
            UPDATE_NOTIFICATION_CHANNEL,
            "PocketPC Updates",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description =
                "Status e continuidade das atualizações do PocketPC."
        }
    )

    val action =
        PendingIntent.getActivity(
            context,
            POST_UPDATE_NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE,
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
                "PocketPC atualizado"
            )
            .setContentText(
                "A nova versão foi instalada. Toque para abrir se ela não reabrir automaticamente."
            )
            .setCategory(Notification.CATEGORY_SYSTEM)
            .setAutoCancel(true)
            .setContentIntent(action)
            .build()

    runCatching {
        manager.notify(
            POST_UPDATE_NOTIFICATION_ID,
            notification,
        )
    }
}
