package dev.pocketpc.core.desktop

import android.content.Context

class DesktopPinStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            "pocketpc-desktop",
            Context.MODE_PRIVATE,
        )

    fun load(): List<DesktopApp> {
        val raw = preferences.getString(KEY, null)
        if (raw.isNullOrBlank()) {
            return defaultPins()
        }

        val parsed = raw
            .split(',')
            .mapNotNull { name ->
                DesktopApp.entries.firstOrNull {
                    it.name == name.trim()
                }
            }
            .distinct()

        return if (parsed.isEmpty()) defaultPins() else parsed
    }

    fun save(apps: List<DesktopApp>) {
        val normalized = apps.distinct()
        preferences.edit()
            .putString(KEY, normalized.joinToString(",") { it.name })
            .apply()
    }

    companion object {
        private const val KEY = "pinned_apps"

        fun defaultPins(): List<DesktopApp> =
            listOf(
                DesktopApp.FILES,
                DesktopApp.BROWSER,
                DesktopApp.TERMINAL,
                DesktopApp.APPS,
            )
    }
}
