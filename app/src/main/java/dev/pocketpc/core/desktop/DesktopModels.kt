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

enum class DesktopApp(
    val label: String,
    val glyph: String,
    val accentArgb: Long,
) {
    BROWSER("Navegador", "WWW", 0xFF1677FFFF),
    FILES("Arquivos", "DIR", 0xFFFFB020FF),
    TERMINAL("Terminal", ">_", 0xFF30343BFF),
    APPS("Aplicativos", "APP", 0xFF7C4DFFFF),
    DOWNLOADS("Downloads", "DL", 0xFF20B968FF),
    PERSONALIZATION("Personalizacao", "IMG", 0xFFEC6A5CFF),
    RUNTIMES("Runtimes", "RT", 0xFF8B5CF6FF),
    SYSTEM("Sistema", "SYS", 0xFF607D8BFF),
    PERFORMANCE("Desempenho", "FPS", 0xFF00A86BFF),
}
