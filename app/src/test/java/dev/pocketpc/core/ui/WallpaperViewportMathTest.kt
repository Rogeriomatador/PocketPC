package dev.pocketpc.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperViewportMathTest {
    @Test
    fun portraitPhotoCanPanVerticallyAtCropZoomOne() {
        val geometry =
            wallpaperViewportGeometry(
                imageWidthPx = 1080,
                imageHeightPx = 2400,
                viewportWidthPx = 1000f,
                viewportHeightPx = 500f,
                fitMode = WallpaperFitMode.CROP,
                zoom = 1f,
            )

        assertEquals(0f, geometry.overflowXpx, 0.01f)
        assertTrue(geometry.overflowYpx > 0f)

        val moved =
            WallpaperTransform()
                .panByPixels(
                    panXpx = 0f,
                    panYpx = 100f,
                    geometry = geometry,
                )

        assertTrue(moved.offsetY > 0f)
    }

    @Test
    fun landscapePhotoCanPanHorizontallyAtCropZoomOne() {
        val geometry =
            wallpaperViewportGeometry(
                imageWidthPx = 2400,
                imageHeightPx = 1080,
                viewportWidthPx = 500f,
                viewportHeightPx = 1000f,
                fitMode = WallpaperFitMode.CROP,
                zoom = 1f,
            )

        assertTrue(geometry.overflowXpx > 0f)
        assertEquals(0f, geometry.overflowYpx, 0.01f)
    }

    @Test
    fun fitAtZoomOneStaysCenteredWhenThereIsNoOverflow() {
        val geometry =
            wallpaperViewportGeometry(
                imageWidthPx = 1080,
                imageHeightPx = 2400,
                viewportWidthPx = 1000f,
                viewportHeightPx = 500f,
                fitMode = WallpaperFitMode.FIT,
                zoom = 1f,
            )

        val moved =
            WallpaperTransform(
                fitMode = WallpaperFitMode.FIT,
            ).panByPixels(
                panXpx = 200f,
                panYpx = 200f,
                geometry = geometry,
            )

        assertEquals(0f, moved.offsetX, 0.0001f)
        assertEquals(0f, moved.offsetY, 0.0001f)
    }

    @Test
    fun zoomCreatesAdditionalPanRange() {
        val base =
            wallpaperViewportGeometry(
                imageWidthPx = 1000,
                imageHeightPx = 1000,
                viewportWidthPx = 1000f,
                viewportHeightPx = 1000f,
                fitMode = WallpaperFitMode.CROP,
                zoom = 1f,
            )
        val zoomed =
            wallpaperViewportGeometry(
                imageWidthPx = 1000,
                imageHeightPx = 1000,
                viewportWidthPx = 1000f,
                viewportHeightPx = 1000f,
                fitMode = WallpaperFitMode.CROP,
                zoom = 2f,
            )

        assertEquals(0f, base.overflowXpx, 0.01f)
        assertTrue(zoomed.overflowXpx > 0f)
        assertTrue(zoomed.overflowYpx > 0f)
    }
}
