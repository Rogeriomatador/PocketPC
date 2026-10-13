package dev.pocketpc.core.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopPointerCommandBridgeTest {
    @Test
    fun recordedAnchorIsConsumedOnlyOnce() {
        DesktopPointerCommandBridge.clear()

        DesktopPointerCommandBridge.record(
            x = 345,
            y = 678,
        )

        assertEquals(
            DesktopPointerAnchor(
                x = 345,
                y = 678,
            ),
            DesktopPointerCommandBridge
                .consume(),
        )
        assertNull(
            DesktopPointerCommandBridge
                .consume(),
        )
    }

    @Test
    fun negativeCoordinatesAreClamped() {
        DesktopPointerCommandBridge.clear()

        DesktopPointerCommandBridge.record(
            x = -10,
            y = -20,
        )

        assertEquals(
            DesktopPointerAnchor(
                x = 0,
                y = 0,
            ),
            DesktopPointerCommandBridge
                .consume(),
        )
    }
}
