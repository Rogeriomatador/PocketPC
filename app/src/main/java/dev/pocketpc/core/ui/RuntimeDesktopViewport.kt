package dev.pocketpc.core.ui

import kotlin.math.min

internal data class RuntimeWindowRectangle(val x: Int, val y: Int, val width: Int, val height: Int)

internal data class RuntimeDesktopViewport(val originX: Float, val originY: Float, val scale: Float) {
    fun x(guestX: Int): Float = (guestX - originX) * scale
    fun y(guestY: Int): Float = (guestY - originY) * scale

    companion object {
        /** A single transform preserves aspect ratios, relative positions and dialog overlap. */
        fun fit(windows: List<RuntimeWindowRectangle>, width: Float, height: Float): RuntimeDesktopViewport {
            if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) {
                return RuntimeDesktopViewport(0f, 0f, 1f)
            }
            val valid = windows.filter { it.width > 0 && it.height > 0 }
            val left = minOf(0L, valid.minOfOrNull { it.x.toLong() } ?: 0L)
            val top = minOf(0L, valid.minOfOrNull { it.y.toLong() } ?: 0L)
            val right = maxOf(1L, valid.maxOfOrNull { it.x.toLong() + it.width } ?: 1L)
            val bottom = maxOf(1L, valid.maxOfOrNull { it.y.toLong() + it.height } ?: 1L)
            val scale = min(1f, min(width / (right - left).toFloat(), height / (bottom - top).toFloat()))
            return RuntimeDesktopViewport(left.toFloat(), top.toFloat(), scale)
        }
    }
}
