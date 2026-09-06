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
    private var nextWindowId = 1L

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
            windows[existing] = current.copy(minimized = false, zIndex = allocateZ())
        } else {
            windows += DesktopWindow(
                id = "${app.name.lowercase()}-${nextWindowId++}",
                title = app.label,
                app = app,
                zIndex = allocateZ(),
            )
        }
        startMenuOpen = false
    }

    fun focus(id: String) {
        mutate(id) { it.copy(zIndex = allocateZ()) }
    }

    fun close(id: String) {
        windows.removeAll { it.id == id }
    }

    fun minimize(id: String) {
        mutate(id) { it.copy(minimized = true) }
    }

    fun toggleMaximize(id: String) {
        mutate(id) { window ->
            window.copy(
                maximized = !window.maximized,
                minimized = false,
                zIndex = allocateZ(),
            )
        }
    }

    private fun mutate(id: String, transform: (DesktopWindow) -> DesktopWindow) {
        val index = windows.indexOfFirst { it.id == id }
        if (index >= 0) windows[index] = transform(windows[index])
    }

    private fun allocateZ(): Int = nextZ++
}
