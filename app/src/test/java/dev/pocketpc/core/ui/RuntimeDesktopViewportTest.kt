package dev.pocketpc.core.ui

import org.junit.Assert.*
import org.junit.Test

class RuntimeDesktopViewportTest {
    @Test fun portraitPreservesWindowAspectAndDialogOverlap() {
        val viewport = RuntimeDesktopViewport.fit(listOf(
            RuntimeWindowRectangle(0, 0, 1280, 720), RuntimeWindowRectangle(400, 200, 480, 320)), 360f, 640f)
        assertEquals(360f / 1280f, viewport.scale, 0.00001f)
        assertEquals(112.5f, viewport.x(400), 0.001f)
        assertEquals(56.25f, viewport.y(200), 0.001f)
        assertEquals(202.5f, 720 * viewport.scale, 0.001f)
    }
    @Test fun includesNegativeCoordinatesWithoutOverflow() {
        val viewport = RuntimeDesktopViewport.fit(listOf(RuntimeWindowRectangle(-200, -100, 1000, 800)), 500f, 400f)
        assertEquals(0f, viewport.x(-200), 0f)
        assertEquals(0f, viewport.y(-100), 0f)
        assertEquals(0.5f, viewport.scale, 0f)
        assertTrue(RuntimeDesktopViewport.fit(listOf(RuntimeWindowRectangle(Int.MAX_VALUE, 0, 200, 100)), 360f, 640f).scale > 0)
    }
    @Test fun smallWindowsKeepTheirNativeScale() {
        assertEquals(1f, RuntimeDesktopViewport.fit(listOf(RuntimeWindowRectangle(20, 20, 320, 200)), 1280f, 720f).scale, 0f)
    }
}
