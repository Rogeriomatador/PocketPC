package dev.pocketpc.core.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Available app space after system and keyboard insets, never the physical display. */
internal data class DesktopLayout(val widthDp: Float, val heightDp: Float) {
    val compact: Boolean get() = widthDp < 700f || heightDp < 500f
    val taskbarHeightDp: Float get() = if (compact) 56f else 60f
    val workspaceHeightDp: Float get() = (heightDp - taskbarHeightDp).coerceAtLeast(1f)
    val startMenuWidthDp: Float get() = (widthDp - 16f).coerceIn(1f, 420f)
    val startMenuHeightDp: Float get() = (workspaceHeightDp - 16f).coerceIn(1f, 520f)
}

internal val LocalDesktopLayout = staticCompositionLocalOf { DesktopLayout(360f, 640f) }
