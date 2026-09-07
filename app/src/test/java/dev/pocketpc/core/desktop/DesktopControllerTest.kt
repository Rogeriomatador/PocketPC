package dev.pocketpc.core.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopControllerTest {
    @Test
    fun openingSameAppRestoresExistingWindowInsteadOfDuplicating() {
        val controller = DesktopController()

        controller.open(DesktopApp.FILES)
        val id = controller.windows.single().id
        controller.minimize(id)
        assertTrue(controller.windows.single().minimized)

        controller.open(DesktopApp.FILES)

        assertEquals(1, controller.windows.size)
        assertFalse(controller.windows.single().minimized)
        assertEquals(id, controller.windows.single().id)
    }

    @Test
    fun focusRaisesWindowAbovePreviouslyFocusedWindow() {
        val controller = DesktopController()

        controller.open(DesktopApp.FILES)
        controller.open(DesktopApp.TERMINAL)
        val files = controller.windows.first { it.app == DesktopApp.FILES }
        val terminalBefore = controller.windows.first { it.app == DesktopApp.TERMINAL }

        controller.focus(files.id)

        val filesAfter = controller.windows.first { it.app == DesktopApp.FILES }
        assertTrue(filesAfter.zIndex > terminalBefore.zIndex)
    }

    @Test
    fun maximizeAlsoRestoresMinimizedWindow() {
        val controller = DesktopController()
        controller.open(DesktopApp.PERFORMANCE)
        val id = controller.windows.single().id
        controller.minimize(id)

        controller.toggleMaximize(id)

        val window = controller.windows.single()
        assertTrue(window.maximized)
        assertFalse(window.minimized)
    }

    @Test
    fun closeRemovesWindow() {
        val controller = DesktopController()
        controller.open(DesktopApp.SYSTEM)
        val id = controller.windows.single().id

        controller.close(id)

        assertTrue(controller.windows.isEmpty())
    }

    @Test
    fun pinnedAppsCanBeToggled() {
        val controller = DesktopController()

        assertTrue(DesktopApp.BROWSER in controller.pinnedApps)
        controller.togglePin(DesktopApp.BROWSER)
        assertFalse(DesktopApp.BROWSER in controller.pinnedApps)
        controller.togglePin(DesktopApp.BROWSER)
        assertTrue(DesktopApp.BROWSER in controller.pinnedApps)
    }

    @Test
    fun minimizeAllKeepsWindowsButHidesThem() {
        val controller = DesktopController()
        controller.open(DesktopApp.BROWSER)
        controller.open(DesktopApp.TERMINAL)

        controller.minimizeAll()

        assertEquals(2, controller.windows.size)
        assertTrue(controller.windows.all { it.minimized })
    }

    @Test
    fun closeActiveClosesHighestZWindow() {
        val controller = DesktopController()
        controller.open(DesktopApp.FILES)
        controller.open(DesktopApp.BROWSER)

        assertEquals(DesktopApp.BROWSER, controller.activeWindow?.app)

        controller.closeActive()

        assertEquals(1, controller.windows.size)
        assertEquals(DesktopApp.FILES, controller.windows.single().app)
    }
}
