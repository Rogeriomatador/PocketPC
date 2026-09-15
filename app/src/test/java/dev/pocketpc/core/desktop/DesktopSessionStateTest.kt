package dev.pocketpc.core.desktop

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.*
import org.junit.Test

class DesktopSessionStateTest {
    private val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }

    @Test fun recreationPreservesWindowsAndTheirModes() {
        val desktop = DesktopController()
        desktop.open(DesktopApp.FILES)
        desktop.snapActiveLeft()
        desktop.open(DesktopApp.BROWSER)
        val browserId = desktop.activeWindow!!.id
        desktop.toggleMaximize(browserId)
        desktop.minimize(browserId)
        desktop.open(DesktopApp.RUNTIMES)
        val saver = DesktopController.saver(::defaultDesktopPins, {})
        val saved = with(saver) { scope.save(desktop) }
        val restored = requireNotNull(saver.restore(requireNotNull(saved)))
        assertEquals(desktop.windows.map { it.id }, restored.windows.map { it.id })
        assertEquals(DesktopApp.RUNTIMES, restored.activeWindow!!.app)
        assertEquals(WindowSnap.LEFT, restored.windows.first().snap)
        assertTrue(restored.windows.first { it.id == browserId }.minimized)
        assertTrue(restored.windows.first { it.id == browserId }.maximized)
        restored.close(browserId)
        restored.open(DesktopApp.BROWSER)
        assertNotEquals(browserId, restored.activeWindow!!.id)
    }

    @Test fun emptyDesktopSurvivesRecreation() {
        val saver = DesktopController.saver(::defaultDesktopPins, {})
        val saved = with(saver) { scope.save(DesktopController()) }
        assertTrue(requireNotNull(saver.restore(requireNotNull(saved))).windows.isEmpty())
    }
}
