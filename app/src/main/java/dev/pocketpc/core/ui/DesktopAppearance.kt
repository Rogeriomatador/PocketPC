package dev.pocketpc.core.ui

import android.content.Context
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class WallpaperPreset(
    val key: String,
    val label: String,
    val animated: Boolean,
    val colors: List<Long>,
) {
    BLUE_SKY(
        key = "blue_sky",
        label = "Ceu azul",
        animated = false,
        colors = listOf(0xFF0B2447, 0xFF19376D, 0xFF576CBC),
    ),
    SUNSET(
        key = "sunset",
        label = "Por do sol",
        animated = false,
        colors = listOf(0xFF2D1B69, 0xFFB53D72, 0xFFF28C6A),
    ),
    FOREST(
        key = "forest",
        label = "Floresta",
        animated = false,
        colors = listOf(0xFF071A17, 0xFF0B3D2E, 0xFF1B6B55),
    ),
    AURORA(
        key = "aurora",
        label = "Aurora animada",
        animated = true,
        colors = listOf(0xFF051937, 0xFF004D7A, 0xFF00BF72),
    ),
    NEON(
        key = "neon",
        label = "Neon animado",
        animated = true,
        colors = listOf(0xFF10002B, 0xFF5A189A, 0xFF00B4D8),
    );

    companion object {
        fun fromKey(key: String?): WallpaperPreset =
            entries.firstOrNull { it.key == key } ?: BLUE_SKY
    }
}

class DesktopAppearanceState(context: Context) {
    private val preferences =
        context.getSharedPreferences("pocketpc-desktop", Context.MODE_PRIVATE)

    var wallpaper: WallpaperPreset =
        WallpaperPreset.fromKey(preferences.getString("wallpaper", null))
        private set

    fun selectWallpaper(preset: WallpaperPreset) {
        wallpaper = preset
        preferences.edit().putString("wallpaper", preset.key).apply()
    }
}

@Composable
fun DesktopWallpaper(
    preset: WallpaperPreset,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "PocketPCWallpaper")
    val motion by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12_000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "wallpaperMotion",
    )

    val animatedMotion = if (preset.animated) motion else 0.35f
    val colors = preset.colors.map(::Color)

    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                colors = colors,
                start = Offset(0f, 0f),
                end = Offset(
                    x = 900f + animatedMotion * 1100f,
                    y = 650f + animatedMotion * 450f,
                ),
            )
        )
    )
}

@Composable
fun PersonalizationApp(
    selected: WallpaperPreset,
    onSelect: (WallpaperPreset) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Personalizacao")
        Text(
            "Escolha o visual do desktop. Os presets animados se movem sem " +
                "usar video em segundo plano."
        )

        WallpaperPreset.entries.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { preset ->
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        tonalElevation = if (preset == selected) 8.dp else 2.dp,
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DesktopWallpaper(
                                preset = preset,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .width(160.dp)
                                    .padding(bottom = 40.dp),
                            )
                            Text(preset.label)
                            Text(if (preset.animated) "ANIMADO" else "ESTATICO")
                            if (preset == selected) {
                                Button(onClick = {}) { Text("Em uso") }
                            } else {
                                OutlinedButton(onClick = { onSelect(preset) }) {
                                    Text("Aplicar")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
