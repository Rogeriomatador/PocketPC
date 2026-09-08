package dev.pocketpc.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WallpaperTransformTest {
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
        assertEquals(3f, safe.zoom)
        assertEquals(-1f, safe.offsetX)
        assertEquals(1f, safe.offsetY)
    }

    @Test
    fun defaultTransformIsCenteredCrop() {
        val safe =
            WallpaperTransform().sanitized()

        assertEquals(
            WallpaperFitMode.CROP,
            safe.fitMode,
        )
        assertEquals(1f, safe.zoom)
        assertEquals(0f, safe.offsetX)
        assertEquals(0f, safe.offsetY)
    }
}
