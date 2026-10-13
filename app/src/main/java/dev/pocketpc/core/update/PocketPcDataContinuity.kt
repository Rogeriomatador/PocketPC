package dev.pocketpc.core.update

import android.content.Context
import android.webkit.CookieManager
import dev.pocketpc.core.BuildConfig

private const val DATA_CONTINUITY_PREFS =
    "pocketpc-data-continuity"
private const val KEY_PREPARED_AT_MILLIS =
    "prepared-at-millis"
private const val KEY_PREPARED_VERSION_CODE =
    "prepared-version-code"
private const val KEY_REPLACED_AT_MILLIS =
    "replaced-at-millis"
private const val KEY_REPLACED_VERSION_CODE =
    "replaced-version-code"

internal data class PocketPcDataContinuitySnapshot(
    val preparedAtMillis: Long,
    val preparedVersionCode: Int,
    val replacedAtMillis: Long,
    val replacedVersionCode: Int,
)

/**
 * Coordinates package-update continuity without taking ownership of user data.
 *
 * WebView cookies/WebStorage, SharedPreferences, databases, Wine prefixes,
 * runtime HOME and user-selected SAF/PocketDrive trees remain in their normal
 * package/user-owned locations. This object never copies or deletes them.
 *
 * Immediately before a foreground package replacement we flush WebView cookies
 * to disk. After ACTION_MY_PACKAGE_REPLACED we only record that the replacement
 * completed and flush again; no authentication/session store is cleared.
 */
internal object PocketPcDataContinuity {
    fun prepareForPackageReplacement(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        flushWebAuthenticationState()

        context.applicationContext
            .getSharedPreferences(
                DATA_CONTINUITY_PREFS,
                Context.MODE_PRIVATE,
            )
            .edit()
            .putLong(
                KEY_PREPARED_AT_MILLIS,
                nowMillis,
            )
            .putInt(
                KEY_PREPARED_VERSION_CODE,
                BuildConfig.VERSION_CODE,
            )
            .apply()
    }

    fun recordPackageReplacement(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        // Persistent WebView state already lives inside this package's data
        // directory. Flush, but never clear/recreate it after replacement.
        flushWebAuthenticationState()

        context.applicationContext
            .getSharedPreferences(
                DATA_CONTINUITY_PREFS,
                Context.MODE_PRIVATE,
            )
            .edit()
            .putLong(
                KEY_REPLACED_AT_MILLIS,
                nowMillis,
            )
            .putInt(
                KEY_REPLACED_VERSION_CODE,
                BuildConfig.VERSION_CODE,
            )
            .apply()
    }

    fun snapshot(
        context: Context,
    ): PocketPcDataContinuitySnapshot {
        val prefs =
            context.applicationContext
                .getSharedPreferences(
                    DATA_CONTINUITY_PREFS,
                    Context.MODE_PRIVATE,
                )

        return PocketPcDataContinuitySnapshot(
            preparedAtMillis =
                prefs.getLong(
                    KEY_PREPARED_AT_MILLIS,
                    0L,
                ),
            preparedVersionCode =
                prefs.getInt(
                    KEY_PREPARED_VERSION_CODE,
                    0,
                ),
            replacedAtMillis =
                prefs.getLong(
                    KEY_REPLACED_AT_MILLIS,
                    0L,
                ),
            replacedVersionCode =
                prefs.getInt(
                    KEY_REPLACED_VERSION_CODE,
                    0,
                ),
        )
    }

    fun flushWebAuthenticationState() {
        runCatching {
            CookieManager.getInstance().flush()
        }
    }
}
