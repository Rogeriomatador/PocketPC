package dev.pocketpc.core.ui

import androidx.compose.runtime.staticCompositionLocalOf

internal data class AppViewport(val widthDp: Float, val heightDp: Float)
internal val LocalAppViewport = staticCompositionLocalOf { AppViewport(360f, 480f) }
