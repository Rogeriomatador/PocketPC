package dev.pocketpc.core.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class DesktopController(
    initialPinnedApps: List<DesktopApp> = defaultDesktopPins(),
    private val onPinnedAppsChanged: ((List<DesktopApp>) -> Unit)? = null,
) {
    val windows = mutableStateListOf<DesktopWindow>()
    val pinnedApps = mutableStateListOf<DesktopApp>().apply {
        addAll(initialPinnedApps.distinct())
    }

    var startMenuOpen by mutableStateOf(false)
        private set
    var contextMenuOpen by mutableStateOf(false)
        private set
    var contextMenuTarget by mutableStateOf<DesktopApp?>(null)
        private set

    private var nextZ = 1
    private var nextWindowId = 1L

    val activeWindow: DesktopWindow?
        get() = windows
            .filterNot { it.minimized }
            .maxByOrNull { it.zIndex }

    fun toggleStartMenu() {
        startMenuOpen = !startMenuOpen
        if (startMenuOpen) closeContextMenu()
    }

    fun closeStartMenu() {
        startMenuOpen = false
    }

    fun openContextMenu(app: DesktopApp? = null) {
        contextMenuTarget = app
        contextMenuOpen = true
        startMenuOpen = false
    }

    fun closeContextMenu() {
        contextMenuOpen = false
        contextMenuTarget = null
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
        closeStartMenu()
        closeContextMenu()
    }

    fun focus(id: String) {
        mutate(id) { it.copy(zIndex = allocateZ()) }
    }

    fun close(id: String) {
        windows.removeAll { it.id == id }
    }

    fun closeActive() {
        activeWindow?.let { close(it.id) }
    }

    fun minimize(id: String) {
        mutate(id) { it.copy(minimized = true) }
    }

    fun minimizeAll() {
        windows.indices.forEach { index ->
            windows[index] = windows[index].copy(minimized = true)
        }
        closeStartMenu()
        closeContextMenu()
    }

    fun cycleWindows() {
        val candidates = windows.filterNot { it.minimized }
        if (candidates.isEmpty()) {
            windows.maxByOrNull { it.zIndex }?.let { open(it.app) }
            return
        }
        val active = activeWindow ?: return
        val ordered = candidates.sortedBy { it.zIndex }
        val index = ordered.indexOfFirst { it.id == active.id }
        val next = ordered[(index + 1).mod(ordered.size)]
        focus(next.id)
    }

    fun toggleMaximize(id: String) {
        mutate(id) { window ->
            window.copy(
                maximized = !window.maximized,
                minimized = false,
                snap = WindowSnap.NONE,
                zIndex = allocateZ(),
            )
        }
    }

    fun snapActiveLeft() {
        activeWindow?.let { snapLeft(it.id) }
    }

    fun snapActiveRight() {
        activeWindow?.let { snapRight(it.id) }
    }

    fun snapLeft(id: String) {
        snap(id, WindowSnap.LEFT)
    }

    fun snapRight(id: String) {
        snap(id, WindowSnap.RIGHT)
    }

    fun restoreActiveSnap() {
        activeWindow?.let { window -> restoreSnap(window.id) }
    }

    fun restoreSnap(id: String) {
        mutate(id) {
            it.copy(
                snap = WindowSnap.NONE,
                maximized = false,
                minimized = false,
                zIndex = allocateZ(),
            )
        }
    }

    private fun snap(id: String, snap: WindowSnap) {
        mutate(id) {
            it.copy(
                snap = snap,
                maximized = false,
                minimized = false,
                zIndex = allocateZ(),
            )
        }
    }

    fun togglePin(app: DesktopApp) {
        if (app in pinnedApps) {
            pinnedApps.remove(app)
        } else {
            pinnedApps.add(app)
        }
        onPinnedAppsChanged?.invoke(pinnedApps.toList())
    }

    private fun mutate(id: String, transform: (DesktopWindow) -> DesktopWindow) {
        val index = windows.indexOfFirst { it.id == id }
        if (index >= 0) windows[index] = transform(windows[index])
    }

    private fun allocateZ(): Int = nextZ++
}
