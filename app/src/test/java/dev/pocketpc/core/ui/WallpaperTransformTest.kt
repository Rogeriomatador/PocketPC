package dev.pocketpc.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WallpaperTransformTest {
    @org.junit.Test
    fun nonFiniteValuesCannotReachTheRenderer() {
        val safe = WallpaperTransform(zoom = Float.NaN, offsetX = Float.POSITIVE_INFINITY, offsetY = Float.NaN).sanitized()
        org.junit.Assert.assertEquals(1f, safe.zoom, 0f)
        org.junit.Assert.assertEquals(0f, safe.offsetX, 0f)
        org.junit.Assert.assertEquals(0f, safe.offsetY, 0f)
    }

    @Test
    fun sanitizeClampsZoomAndOffsets() {
        val safe =
            WallpaperTransform(
                fitMode = WallpaperFitMode.FIT,
                zoom = 8f,
                offsetX = -4f,
                offsetY = 5f,
            ).sanitized()

        assertEquals(
            WallpaperFitMode.FIT,
            safe.fitMode,
        )
        assertEquals(3f, safe.zoom, 0.0001f)
        assertEquals(-1f, safe.offsetX, 0.0001f)
        assertEquals(1f, safe.offsetY, 0.0001f)
    }

    @Test
    fun simpleSolidWallpaperPresetsRemainAvailable() {
        assertEquals(
            WallpaperPreset.SOLID_BLACK,
            WallpaperPreset.fromKey(
                "solid_black"
            ),
        )
        assertEquals(
            WallpaperPreset.SOLID_WHITE,
            WallpaperPreset.fromKey(
                "solid_white"
            ),
        )
        assertEquals(
            1,
            WallpaperPreset.SOLID_BLACK
                .colors.distinct().size,
        )
        assertEquals(
            1,
            WallpaperPreset.SOLID_WHITE
                .colors.distinct().size,
        )
    }

    @Test
    fun defaultTransformIsCenteredCrop() {
        val safe =
            WallpaperTransform().sanitized()

        assertEquals(
            WallpaperFitMode.CROP,
            safe.fitMode,
        )
        assertEquals(1f, safe.zoom, 0.0001f)
        assertEquals(0f, safe.offsetX, 0.0001f)
        assertEquals(0f, safe.offsetY, 0.0001f)
    }
}
