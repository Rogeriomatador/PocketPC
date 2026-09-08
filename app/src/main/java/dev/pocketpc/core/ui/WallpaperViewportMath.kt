package dev.pocketpc.core.ui

import kotlin.math.max
import kotlin.math.min

internal data class WallpaperViewportGeometry(
    val renderedWidthPx: Float,
    val renderedHeightPx: Float,
    val overflowXpx: Float,
    val overflowYpx: Float,
)

internal fun wallpaperViewportGeometry(
    imageWidthPx: Int,
    imageHeightPx: Int,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    fitMode: WallpaperFitMode,
    zoom: Float,
): WallpaperViewportGeometry {
    val imageWidth =
        imageWidthPx.coerceAtLeast(1).toFloat()
    val imageHeight =
        imageHeightPx.coerceAtLeast(1).toFloat()
    val viewportWidth =
        viewportWidthPx.coerceAtLeast(1f)
    val viewportHeight =
        viewportHeightPx.coerceAtLeast(1f)
    val safeZoom = zoom.coerceIn(1f, 3f)

    val widthScale = viewportWidth / imageWidth
    val heightScale = viewportHeight / imageHeight
    val baseScale =
        when (fitMode) {
            WallpaperFitMode.CROP ->
                max(widthScale, heightScale)

            WallpaperFitMode.FIT ->
                min(widthScale, heightScale)
        }

    val renderedWidth =
        imageWidth * baseScale * safeZoom
    val renderedHeight =
        imageHeight * baseScale * safeZoom

    return WallpaperViewportGeometry(
        renderedWidthPx = renderedWidth,
        renderedHeightPx = renderedHeight,
        overflowXpx =
            ((renderedWidth - viewportWidth) / 2f)
                .coerceAtLeast(0f),
        overflowYpx =
            ((renderedHeight - viewportHeight) / 2f)
                .coerceAtLeast(0f),
    )
}

internal fun WallpaperTransform.panByPixels(
    panXpx: Float,
    panYpx: Float,
    geometry: WallpaperViewportGeometry,
): WallpaperTransform {
    val safe = sanitized()

    return safe.copy(
        offsetX =
            if (geometry.overflowXpx > 0.5f) {
                (
                    safe.offsetX +
                        panXpx /
                        geometry.overflowXpx
                ).coerceIn(-1f, 1f)
            } else {
                0f
            },
        offsetY =
            if (geometry.overflowYpx > 0.5f) {
                (
                    safe.offsetY +
                        panYpx /
                        geometry.overflowYpx
                ).coerceIn(-1f, 1f)
            } else {
                0f
            },
    )
}
