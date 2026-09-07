package dev.pocketpc.core.desktop

import android.content.Context

data class WindowGeometry(
    val xFraction: Float,
    val yFraction: Float,
    val widthFraction: Float,
    val heightFraction: Float,
) {
    fun sanitized(): WindowGeometry =
        copy(
            xFraction = xFraction.coerceIn(0f, 0.85f),
            yFraction = yFraction.coerceIn(0f, 0.80f),
            widthFraction = widthFraction.coerceIn(0.38f, 0.95f),
            heightFraction = heightFraction.coerceIn(0.42f, 0.92f),
        )
}

class DesktopWindowLayoutStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            "pocketpc-window-layout",
            Context.MODE_PRIVATE,
        )

    fun load(app: DesktopApp): WindowGeometry? {
        val prefix = prefix(app)
        if (!preferences.contains("${prefix}width")) {
            return null
        }

        return WindowGeometry(
            xFraction = preferences.getFloat("${prefix}x", 0.08f),
            yFraction = preferences.getFloat("${prefix}y", 0.10f),
            widthFraction = preferences.getFloat("${prefix}width", 0.72f),
            heightFraction = preferences.getFloat("${prefix}height", 0.70f),
        ).sanitized()
    }

    fun save(app: DesktopApp, geometry: WindowGeometry) {
        val value = geometry.sanitized()
        val prefix = prefix(app)
        preferences.edit()
            .putFloat("${prefix}x", value.xFraction)
            .putFloat("${prefix}y", value.yFraction)
            .putFloat("${prefix}width", value.widthFraction)
            .putFloat("${prefix}height", value.heightFraction)
            .apply()
    }

    fun clear(app: DesktopApp) {
        val prefix = prefix(app)
        preferences.edit()
            .remove("${prefix}x")
            .remove("${prefix}y")
            .remove("${prefix}width")
            .remove("${prefix}height")
            .apply()
    }

    fun clearAll() {
        preferences.edit().clear().apply()
    }

    private fun prefix(app: DesktopApp): String =
        "${app.name.lowercase()}."
}
