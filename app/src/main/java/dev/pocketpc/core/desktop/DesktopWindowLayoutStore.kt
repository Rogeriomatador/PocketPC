package dev.pocketpc.core.desktop

import android.content.Context

data class WindowGeometry(
    val xFraction: Float,
    val yFraction: Float,
    val widthFraction: Float,
    val heightFraction: Float,
) {
    fun sanitized(spec: DesktopWindowSpec): WindowGeometry {
        val width = widthFraction.coerceIn(
            0.20f,
            spec.maxWidthFraction,
        )
        val height = heightFraction.coerceIn(
            0.20f,
            spec.maxHeightFraction,
        )
        return copy(
            xFraction = xFraction.coerceIn(
                0f,
                (1f - width).coerceAtLeast(0f),
            ),
            yFraction = yFraction.coerceIn(
                0f,
                (1f - height).coerceAtLeast(0f),
            ),
            widthFraction = width,
            heightFraction = height,
        )
    }
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
            widthFraction = preferences.getFloat(
                "${prefix}width",
                app.windowSpec().defaultWidthFraction,
            ),
            heightFraction = preferences.getFloat(
                "${prefix}height",
                app.windowSpec().defaultHeightFraction,
            ),
        ).sanitized(app.windowSpec())
    }

    fun save(app: DesktopApp, geometry: WindowGeometry) {
        val value = geometry.sanitized(app.windowSpec())
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
