package dev.pocketpc.core.desktop

import androidx.compose.runtime.Immutable

@Immutable
data class DesktopWindow(
    val id: String,
    val title: String,
    val app: DesktopApp,
    val minimized: Boolean = false,
    val maximized: Boolean = false,
    val zIndex: Int = 0,
)

enum class DesktopApp(val label: String, val glyph: String) {
    FILES("Arquivos", "▣"),
    TERMINAL("Terminal", ">_"),
    RUNTIMES("Runtimes", "⬡"),
    SYSTEM("Sistema", "◉"),
    PERFORMANCE("Desempenho", "⌁"),
}
