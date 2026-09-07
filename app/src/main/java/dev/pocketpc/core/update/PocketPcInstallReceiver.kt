package dev.pocketpc.core.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

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
                "pocketpc-updater",
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

            confirmation?.apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }?.let(context::startActivity)
        }
    }
}
