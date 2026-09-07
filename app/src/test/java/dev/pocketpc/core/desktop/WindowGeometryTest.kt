package dev.pocketpc.core.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowGeometryTest {
    @Test
    fun validGeometryIsPreserved() {
        val value = WindowGeometry(
            xFraction = 0.2f,
            yFraction = 0.3f,
            widthFraction = 0.7f,
            heightFraction = 0.6f,
        ).sanitized()

        assertEquals(0.2f, value.xFraction)
        assertEquals(0.3f, value.yFraction)
        assertEquals(0.7f, value.widthFraction)
        assertEquals(0.6f, value.heightFraction)
    }

    @Test
    fun invalidGeometryIsClampedToDesktopBounds() {
        val value = WindowGeometry(
            xFraction = -2f,
            yFraction = 9f,
            widthFraction = 0.1f,
            heightFraction = 3f,
        ).sanitized()

        assertEquals(0f, value.xFraction)
        assertEquals(0.80f, value.yFraction)
        assertEquals(0.38f, value.widthFraction)
        assertEquals(0.92f, value.heightFraction)
    }
}
