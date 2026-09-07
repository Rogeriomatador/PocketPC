package dev.pocketpc.core.desktop

import androidx.compose.runtime.Immutable

enum class WindowSnap {
    NONE,
    LEFT,
    RIGHT,
}

@Immutable
data class DesktopWindow(
    val id: String,
    val title: String,
    val app: DesktopApp,
    val minimized: Boolean = false,
    val maximized: Boolean = false,
    val snap: WindowSnap = WindowSnap.NONE,
    val zIndex: Int = 0,
)

enum class DesktopApp(
    val label: String,
    val glyph: String,
    val accentArgb: Long,
) {
    BROWSER("Navegador", "WWW", 0xFF1677FF),
    FILES("Arquivos", "DIR", 0xFFFFB020),
    TERMINAL("Terminal", ">_", 0xFF30343B),
    APPS("Aplicativos", "APP", 0xFF7C4DFF),
    DOWNLOADS("Downloads", "DL", 0xFF20B968),
    DISPLAYS("Telas", "TV", 0xFF26A69A),
    PERSONALIZATION("Personalizacao", "IMG", 0xFFEC6A5C),
    RUNTIMES("Runtimes", "RT", 0xFF8B5CF6),
    SYSTEM("Sistema", "SYS", 0xFF607D8B),
    PERFORMANCE("Desempenho", "FPS", 0xFF00A86B),
}

fun defaultDesktopPins(): List<DesktopApp> =
    listOf(
        DesktopApp.FILES,
        DesktopApp.BROWSER,
        DesktopApp.TERMINAL,
        DesktopApp.APPS,
    )
