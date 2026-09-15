package dev.pocketpc.core.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class DesktopThemeMode(
    val key: String,
    val label: String,
) {
    SYSTEM("system", "Sistema"),
    LIGHT("light", "Claro"),
    DARK("dark", "Escuro");

    companion object {
        fun fromKey(key: String?): DesktopThemeMode =
            entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

enum class WallpaperPreset(
    val key: String,
    val label: String,
    val animated: Boolean,
    val colors: List<Long>,
) {
    SOLID_BLACK(
        key = "solid_black",
        label = "Preto",
        animated = false,
        colors = listOf(
            0xFF000000,
            0xFF000000,
        ),
    ),
    SOLID_WHITE(
        key = "solid_white",
        label = "Branco",
        animated = false,
        colors = listOf(
            0xFFFFFFFF,
            0xFFFFFFFF,
        ),
    ),
    SOLID_GRAPHITE(
        key = "solid_graphite",
        label = "Grafite",
        animated = false,
        colors = listOf(
            0xFF181A1F,
            0xFF181A1F,
        ),
    ),
    SOLID_GRAY(
        key = "solid_gray",
        label = "Cinza",
        animated = false,
        colors = listOf(
            0xFF6D7278,
            0xFF6D7278,
        ),
    ),
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

enum class WallpaperFitMode(
    val key: String,
    val label: String,
) {
    CROP("crop", "Preencher"),
    FIT("fit", "Encaixar");

    companion object {
        fun fromKey(key: String?): WallpaperFitMode =
            entries.firstOrNull { it.key == key } ?: CROP
    }
}

data class WallpaperTransform(
    val fitMode: WallpaperFitMode = WallpaperFitMode.CROP,
    val zoom: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    fun sanitized(): WallpaperTransform =
        copy(
            zoom = (zoom.takeIf { it.isFinite() } ?: 1f).coerceIn(1f, 3f),
            offsetX = (offsetX.takeIf { it.isFinite() } ?: 0f).coerceIn(-1f, 1f),
            offsetY = (offsetY.takeIf { it.isFinite() } ?: 0f).coerceIn(-1f, 1f),
        )
    companion object {
        val Saver = androidx.compose.runtime.saveable.listSaver<WallpaperTransform, Any>(
            save = { listOf(it.fitMode.key, it.zoom, it.offsetX, it.offsetY) },
            restore = { values -> WallpaperTransform(
                WallpaperFitMode.fromKey(values[0] as String),
                values[1] as Float, values[2] as Float, values[3] as Float,
            ).sanitized() },
        )
    }
}

class DesktopAppearanceState(context: Context) {
    private val preferences =
        context.getSharedPreferences("pocketpc-desktop", Context.MODE_PRIVATE)

    var wallpaper by mutableStateOf(
        WallpaperPreset.fromKey(preferences.getString("wallpaper", null))
    )
        private set

    var themeMode by mutableStateOf(
        DesktopThemeMode.fromKey(preferences.getString("theme", null))
    )
        private set

    var customWallpaperUri by mutableStateOf(
        preferences.getString("custom_wallpaper_uri", null)
    )
        private set

    var customWallpaperTransform by mutableStateOf(
        WallpaperTransform(
            fitMode =
                WallpaperFitMode.fromKey(
                    preferences.getString(
                        "custom_wallpaper_fit_mode",
                        null,
                    )
                ),
            zoom =
                preferences.getFloat(
                    "custom_wallpaper_zoom",
                    1f,
                ),
            offsetX =
                preferences.getFloat(
                    "custom_wallpaper_offset_x",
                    0f,
                ),
            offsetY =
                preferences.getFloat(
                    "custom_wallpaper_offset_y",
                    0f,
                ),
        ).sanitized()
    )
        private set

    var showPerformanceHud by mutableStateOf(
        preferences.getBoolean("performance_hud", false)
    )
        private set

    fun selectTheme(mode: DesktopThemeMode) {
        themeMode = mode
        preferences.edit().putString("theme", mode.key).apply()
    }

    fun selectWallpaper(preset: WallpaperPreset) {
        wallpaper = preset
        customWallpaperUri = null
        customWallpaperTransform =
            WallpaperTransform()
        preferences.edit()
            .putString("wallpaper", preset.key)
            .remove("custom_wallpaper_uri")
            .remove("custom_wallpaper_fit_mode")
            .remove("custom_wallpaper_zoom")
            .remove("custom_wallpaper_offset_x")
            .remove("custom_wallpaper_offset_y")
            .apply()
    }

    fun selectCustomWallpaper(
        uri: String,
        transform: WallpaperTransform =
            WallpaperTransform(),
    ) {
        val safe = transform.sanitized()
        customWallpaperUri = uri
        customWallpaperTransform = safe
        preferences.edit()
            .putString("custom_wallpaper_uri", uri)
            .putString(
                "custom_wallpaper_fit_mode",
                safe.fitMode.key,
            )
            .putFloat(
                "custom_wallpaper_zoom",
                safe.zoom,
            )
            .putFloat(
                "custom_wallpaper_offset_x",
                safe.offsetX,
            )
            .putFloat(
                "custom_wallpaper_offset_y",
                safe.offsetY,
            )
            .apply()
    }

    fun clearCustomWallpaper() {
        customWallpaperUri = null
        customWallpaperTransform =
            WallpaperTransform()
        preferences.edit()
            .remove("custom_wallpaper_uri")
            .remove("custom_wallpaper_fit_mode")
            .remove("custom_wallpaper_zoom")
            .remove("custom_wallpaper_offset_x")
            .remove("custom_wallpaper_offset_y")
            .apply()
    }

    fun setPerformanceHud(enabled: Boolean) {
        showPerformanceHud = enabled
        preferences.edit()
            .putBoolean("performance_hud", enabled)
            .apply()
    }
}

@Composable
fun DesktopWallpaper(
    preset: WallpaperPreset,
    customUri: String? = null,
    customTransform: WallpaperTransform =
        WallpaperTransform(),
    animationEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val animatedMotion: State<Float> =
        if (preset.animated && animationEnabled && customUri.isNullOrBlank()) {
            val transition =
                rememberInfiniteTransition(label = "PocketPCWallpaper")
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 12_000),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "wallpaperMotion",
            )
        } else {
            rememberUpdatedState(0.35f)
        }

    val colors = preset.colors.map { argb -> Color(argb) }
    val backgroundColors =
        if (
            !customUri.isNullOrBlank() &&
            customTransform.fitMode ==
                WallpaperFitMode.FIT
        ) {
            listOf(Color.Black, Color.Black)
        } else {
            colors
        }
    val context = LocalContext.current
    val customBitmap by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = customUri,
    ) {
        value =
            if (customUri.isNullOrBlank()) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    decodeWallpaperBitmap(
                        context = context,
                        uri = Uri.parse(customUri),
                    )
                }
            }
    }

    Box(
        // Read animation state during drawing: no per-frame recomposition of the bitmap/layout.
        modifier = modifier.drawBehind {
            val motion = animatedMotion.value
            drawRect(
                brush = Brush.linearGradient(
                    colors = backgroundColors,
                    start = Offset.Zero,
                    end = Offset(size.width * (0.7f + motion), size.height * (0.8f + motion * 0.4f)),
                )
            )
        }
    ) {
        customBitmap?.let { bitmap ->
            androidx.compose.foundation.layout.BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
            ) {
                val density = LocalDensity.current
                val safe =
                    customTransform.sanitized()
                val widthPx =
                    with(density) {
                        maxWidth.toPx()
                    }
                val heightPx =
                    with(density) {
                        maxHeight.toPx()
                    }
                val geometry =
                    wallpaperViewportGeometry(
                        imageWidthPx = bitmap.width,
                        imageHeightPx = bitmap.height,
                        viewportWidthPx = widthPx,
                        viewportHeightPx = heightPx,
                        fitMode = safe.fitMode,
                        zoom = safe.zoom,
                    )
                val renderedWidth =
                    with(density) {
                        geometry.renderedWidthPx.toDp()
                    }
                val renderedHeight =
                    with(density) {
                        geometry.renderedHeightPx.toDp()
                    }

                Image(
                    bitmap = bitmap,
                    contentDescription =
                        "Papel de parede personalizado",
                    modifier = Modifier
                        .align(
                            androidx.compose.ui.Alignment.Center
                        )
                        .requiredSize(renderedWidth, renderedHeight)
                        .offset {
                            IntOffset(
                                (
                                    safe.offsetX *
                                        geometry.overflowXpx
                                ).roundToInt(),
                                (
                                    safe.offsetY *
                                        geometry.overflowYpx
                                ).roundToInt(),
                            )
                        },
                    contentScale =
                        ContentScale.FillBounds,
                )
            }
        }
    }
}

internal fun decodeWallpaperBitmap(
    context: Context,
    uri: Uri,
): ImageBitmap? =
    runCatching {
        val bitmap =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source =
                    ImageDecoder.createSource(
                        context.contentResolver,
                        uri,
                    )
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val maxDimension =
                        maxOf(info.size.width, info.size.height)
                    if (maxDimension > 4096) {
                        val scale = 4096f / maxDimension.toFloat()
                        decoder.setTargetSize(
                            (info.size.width * scale).toInt()
                                .coerceAtLeast(1),
                            (info.size.height * scale).toInt()
                                .coerceAtLeast(1),
                        )
                    }
                }
            } else {
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input)
                    BitmapFactory.decodeStream(input)
                }
            }

        requireNotNull(bitmap).asImageBitmap()
    }.getOrNull()

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonalizationApp(
    selected: WallpaperPreset,
    customUri: String?,
    themeMode: DesktopThemeMode,
    onSelect: (WallpaperPreset) -> Unit,
    onThemeSelect: (DesktopThemeMode) -> Unit,
    onChooseCustom: () -> Unit,
    onEditCustom: () -> Unit,
    onClearCustom: () -> Unit,
    showPerformanceHud: Boolean,
    onPerformanceHudChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Personalização", style = MaterialTheme.typography.titleMedium)
        Text("Tema")
        FlowRow(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DesktopThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = mode == themeMode,
                    onClick = { onThemeSelect(mode) },
                    label = { Text(mode.label) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }

        Text("Desktop")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("HUD de desempenho", modifier = Modifier.weight(1f))
            Switch(
                checked = showPerformanceHud,
                onCheckedChange = onPerformanceHudChange,
            )
        }

        Text("Papel de parede")

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Button(onClick = onChooseCustom) { Text("Escolher imagem") }
            if (customUri != null) {
                OutlinedButton(onClick = onEditCustom) { Text("Ajustar enquadramento") }
                OutlinedButton(onClick = onClearCustom) { Text("Usar padrão") }
            }
        }
        Text(
            if (customUri != null) "Imagem personalizada em uso" else "Toque em uma opção para aplicar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val solidPresets =
            WallpaperPreset.entries.filter {
                it.colors.distinct().size == 1
            }
        val visualPresets =
            WallpaperPreset.entries.filterNot {
                it.colors.distinct().size == 1
            }

        Text(
            "Cores sólidas",
            style =
                MaterialTheme.typography.titleSmall,
        )
        Text(
            "Opções simples e leves para quem quer um desktop limpo.",
            style =
                MaterialTheme.typography.bodySmall,
            color =
                MaterialTheme.colorScheme
                    .onSurfaceVariant,
        )
        WallpaperPresetGrid(
            presets = solidPresets,
            selected = selected,
            customUri = customUri,
            onSelect = onSelect,
        )

        Text(
            "Gradientes e animações",
            style =
                MaterialTheme.typography.titleSmall,
        )
        WallpaperPresetGrid(
            presets = visualPresets,
            selected = selected,
            customUri = customUri,
            onSelect = onSelect,
        )
    }
}

@Composable
private fun WallpaperPresetGrid(
    presets: List<WallpaperPreset>,
    selected: WallpaperPreset,
    customUri: String?,
    onSelect: (WallpaperPreset) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Measure the app window, and allow labels to grow with the system font.
        val minimumTileWidth = 156.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        val columns = ((maxWidth + 8.dp) / (minimumTileWidth + 8.dp)).toInt().coerceIn(1, 4)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { preset ->
                        val inUse = preset == selected && customUri == null
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            color = if (inUse) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(
                                if (inUse) 2.dp else 1.dp,
                                if (inUse) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .selectable(selected = inUse, role = Role.RadioButton,
                                        onClick = { onSelect(preset) })
                                    .heightIn(min = 64.dp)
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                // A static swatch avoids running every animated preset at once.
                                Surface(shape = RoundedCornerShape(8.dp)) {
                                    DesktopWallpaper(preset = preset, animationEnabled = false,
                                        modifier = Modifier.size(36.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(preset.label, style = MaterialTheme.typography.bodyMedium)
                                    if (inUse || preset.animated) {
                                        Text(if (inUse) "Em uso" else "Animado",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
