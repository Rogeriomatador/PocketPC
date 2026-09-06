package dev.pocketpc.core.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class DesktopController {
    val windows = mutableStateListOf<DesktopWindow>()
    var startMenuOpen by mutableStateOf(false)
        private set

    private var nextZ = 1

    fun toggleStartMenu() {
        startMenuOpen = !startMenuOpen
    }

    fun closeStartMenu() {
        startMenuOpen = false
    }

    fun open(app: DesktopApp) {
        val existing = windows.indexOfFirst { it.app == app }
        if (existing >= 0) {
            val current = windows[existing]
            windows[existing] = current.copy(minimized = false, zIndex = nextZ++)
        } else {
            windows += DesktopWindow(
                id = "${app.name.lowercase()}-${System.nanoTime()}",
                title = app.label,
                app = app,
                zIndex = nextZ++,
            )
        }
        startMenuOpen = false
    }

    fun focus(id: String) {
        val index = windows.indexOfFirst { it.id == id }
        if (index >= 0) windows[index] = windows[index].copy(zIndex = nextZ++)
    }

    fun close(id: String) {
        windows.removeAll { it.id == id }
    }

    fun minimize(id: String) {
        val index = windows.indexOfFirst { it.id == id }
        if (index >= 0) windows[index] = windows[index].copy(minimized = true)
    }

    fun toggleMaximize(id: String) {
        val index = windows.indexOfFirst { it.id == id }
        if (index >= 0) {
            val w = windows[index]
            windows[index] = w.copy(maximized = !w.maximized, minimized = false, zIndex = nextZ++)
        }
    }
}
