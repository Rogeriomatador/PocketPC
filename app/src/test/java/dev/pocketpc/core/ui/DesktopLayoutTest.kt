package dev.pocketpc.core.ui

import org.junit.Assert.*
import org.junit.Test

class DesktopLayoutTest {
    @Test fun phoneAndLandscapeUseFullWorkspaceWindows() {
        assertTrue(DesktopLayout(360f, 800f).compact)
        assertTrue(DesktopLayout(800f, 360f).compact)
        assertFalse(DesktopLayout(1280f, 720f).compact)
    }

    @Test fun keyboardAndSplitScreenLeaveMenuInsideAvailableSpace() {
        for (layout in listOf(DesktopLayout(360f, 800f), DesktopLayout(360f, 300f),
            DesktopLayout(800f, 220f), DesktopLayout(280f, 180f), DesktopLayout(1280f, 720f))) {
            assertTrue(layout.startMenuWidthDp > 0f)
            assertTrue(layout.startMenuWidthDp + 16f <= layout.widthDp)
            assertTrue(layout.startMenuHeightDp > 0f)
            assertTrue(layout.startMenuHeightDp + 16f <= layout.workspaceHeightDp)
            assertTrue(layout.workspaceHeightDp + layout.taskbarHeightDp <= layout.heightDp)
        }
    }

    @Test fun resizingToSmallWindowDoesNotKeepDesktopSizedMenu() {
        val expanded = DesktopLayout(1280f, 800f)
        val resized = expanded.copy(widthDp = 320f, heightDp = 280f)
        assertTrue(resized.compact)
        assertTrue(resized.startMenuWidthDp < expanded.startMenuWidthDp)
        assertTrue(resized.startMenuHeightDp < expanded.startMenuHeightDp)
    }
}
