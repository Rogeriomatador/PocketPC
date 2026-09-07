package dev.pocketpc.core.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowGeometryTest {
    @Test
    fun validGeometryIsPreservedInsideAppContract() {
        val spec = DesktopApp.FILES.windowSpec()

        val value = WindowGeometry(
            xFraction = 0.1f,
            yFraction = 0.1f,
            widthFraction = 0.7f,
            heightFraction = 0.6f,
        ).sanitized(spec)

        assertEquals(0.1f, value.xFraction, 0.0001f)
        assertEquals(0.1f, value.yFraction, 0.0001f)
        assertEquals(0.7f, value.widthFraction, 0.0001f)
        assertEquals(0.6f, value.heightFraction, 0.0001f)
    }

    @Test
    fun invalidGeometryIsClampedToAppAndDesktopBounds() {
        val spec = DesktopApp.FILES.windowSpec()

        val value = WindowGeometry(
            xFraction = -2f,
            yFraction = 9f,
            widthFraction = 0.1f,
            heightFraction = 3f,
        ).sanitized(spec)

        assertEquals(0f, value.xFraction, 0.0001f)
        assertEquals(0.08f, value.yFraction, 0.0001f)
        assertEquals(0.20f, value.widthFraction, 0.0001f)
        assertEquals(0.92f, value.heightFraction, 0.0001f)
    }

    @Test
    fun browserAllowsFullWorkspaceGeometry() {
        val spec = DesktopApp.BROWSER.windowSpec()

        val value = WindowGeometry(
            xFraction = 0f,
            yFraction = 0f,
            widthFraction = 1f,
            heightFraction = 1f,
        ).sanitized(spec)

        assertEquals(1f, value.widthFraction, 0.0001f)
        assertEquals(1f, value.heightFraction, 0.0001f)
        assertEquals(0f, value.xFraction, 0.0001f)
        assertEquals(0f, value.yFraction, 0.0001f)
    }
}
