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

data class DesktopWindowSpec(
    val defaultWidthFraction: Float,
    val defaultHeightFraction: Float,
    val minWidthDp: Int,
    val minHeightDp: Int,
    val defaultXFraction: Float = 0.08f,
    val defaultYFraction: Float = 0.08f,
    val maxWidthFraction: Float = 0.96f,
    val maxHeightFraction: Float = 0.92f,
    val contentPaddingDp: Int = 12,
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
    STORE("Loja", "SHOP", 0xFF34A853),
    CONTROL_CENTER("Central", "CTL", 0xFF455A64),
    DISPLAYS("Telas", "TV", 0xFF26A69A),
    PERSONALIZATION("Personalizacao", "IMG", 0xFFEC6A5C),
    RUNTIMES("Runtimes", "RT", 0xFF8B5CF6),
    SYSTEM("Este PC", "SYS", 0xFF607D8B),
    PERFORMANCE("Desempenho", "FPS", 0xFF00A86B),
    TASK_MANAGER("Gerenciador de Tarefas", "TASK", 0xFF3B82F6),
}

fun DesktopApp.windowSpec(): DesktopWindowSpec =
    when (this) {
        DesktopApp.BROWSER ->
            DesktopWindowSpec(
                defaultWidthFraction = 0.94f,
                defaultHeightFraction = 0.88f,
                minWidthDp = 560,
                minHeightDp = 320,
                defaultXFraction = 0.03f,
                defaultYFraction = 0.03f,
                maxWidthFraction = 1.0f,
                maxHeightFraction = 1.0f,
                contentPaddingDp = 0,
            )
        DesktopApp.FILES ->
            DesktopWindowSpec(
                defaultWidthFraction = 0.82f,
                defaultHeightFraction = 0.80f,
                minWidthDp = 520,
                minHeightDp = 320,
                defaultXFraction = 0.06f,
                defaultYFraction = 0.06f,
                contentPaddingDp = 0,
            )
        DesktopApp.TERMINAL ->
            DesktopWindowSpec(0.72f, 0.70f, 420, 280, 0.12f, 0.10f, contentPaddingDp = 8)
        DesktopApp.APPS ->
            DesktopWindowSpec(0.80f, 0.80f, 540, 330, 0.08f, 0.07f, contentPaddingDp = 10)
        DesktopApp.DOWNLOADS ->
            DesktopWindowSpec(0.60f, 0.56f, 420, 260, 0.20f, 0.16f)
        DesktopApp.STORE ->
            DesktopWindowSpec(0.72f, 0.72f, 480, 300, 0.14f, 0.10f)
        DesktopApp.CONTROL_CENTER ->
            DesktopWindowSpec(0.62f, 0.62f, 470, 290, 0.19f, 0.13f, contentPaddingDp = 12)
        DesktopApp.DISPLAYS ->
            DesktopWindowSpec(0.66f, 0.68f, 460, 300, 0.17f, 0.11f)
        DesktopApp.PERSONALIZATION ->
            DesktopWindowSpec(0.78f, 0.82f, 520, 330, 0.10f, 0.06f)
        DesktopApp.RUNTIMES ->
            DesktopWindowSpec(0.78f, 0.82f, 520, 330)
        DesktopApp.SYSTEM ->
            DesktopWindowSpec(0.84f, 0.84f, 560, 340, 0.07f, 0.05f, contentPaddingDp = 10)
        DesktopApp.PERFORMANCE ->
            DesktopWindowSpec(0.60f, 0.64f, 420, 280, 0.20f, 0.12f)
        DesktopApp.TASK_MANAGER ->
            DesktopWindowSpec(
                defaultWidthFraction = 0.76f,
                defaultHeightFraction = 0.76f,
                minWidthDp = 520,
                minHeightDp = 320,
                defaultXFraction = 0.12f,
                defaultYFraction = 0.08f,
                contentPaddingDp = 8,
            )
    }

fun defaultDesktopPins(): List<DesktopApp> =
    listOf(
        DesktopApp.FILES,
        DesktopApp.BROWSER,
        DesktopApp.TERMINAL,
        DesktopApp.APPS,
    )


fun defaultDesktopShortcuts(): List<DesktopApp> =
    listOf(
        DesktopApp.BROWSER,
        DesktopApp.FILES,
        DesktopApp.APPS,
        DesktopApp.STORE,
        DesktopApp.CONTROL_CENTER,
        DesktopApp.DOWNLOADS,
        DesktopApp.PERSONALIZATION,
        DesktopApp.SYSTEM,
    )
