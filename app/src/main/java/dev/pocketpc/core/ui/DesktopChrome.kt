package dev.pocketpc.core.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketpc.core.BuildConfig
import dev.pocketpc.core.desktop.DesktopApp
import dev.pocketpc.core.desktop.DesktopController
import dev.pocketpc.core.desktop.PeripheralSnapshot
import dev.pocketpc.core.desktop.defaultDesktopShortcuts
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DesktopIconsV2(
    desktop: DesktopController,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val compactMobile =
        configuration.screenWidthDp < 700 ||
            configuration.screenHeightDp < 500
    val iconSize = if (compactMobile) 42 else 48
    val itemWidth = if (compactMobile) 78.dp else 92.dp
    val columnWidth = if (compactMobile) 186.dp else 220.dp
    val spacing = if (compactMobile) 8.dp else 12.dp

    Column(
        modifier = modifier.width(columnWidth),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        defaultDesktopShortcuts().chunked(2).forEach { rowApps ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                rowApps.forEach { app ->
                    val hoverSource = remember(app) { MutableInteractionSource() }
                    val hovered by hoverSource.collectIsHoveredAsState()
                    val focused by hoverSource.collectIsFocusedAsState()

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(itemWidth)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .hoverable(hoverSource)
                            .focusable(interactionSource = hoverSource)
                            .border(
                                width = if (focused) 2.dp else 0.dp,
                                color =
                                    if (focused) {
                                        Color.White.copy(alpha = 0.85f)
                                    } else {
                                        Color.Transparent
                                    },
                                shape = RoundedCornerShape(12.dp),
                            )
                            .background(
                                if (hovered || focused) {
                                    Color.White.copy(alpha = 0.10f)
                                } else {
                                    Color.Transparent
                                },
                                RoundedCornerShape(12.dp),
                            )
                            .desktopSecondaryClick {
                                desktop.openContextMenu(app)
                            }
                            .combinedClickable(
                                onClick = { desktop.open(app) },
                                onLongClick = { desktop.openContextMenu(app) },
                            )
                            .padding(4.dp),
                    ) {
                        AppIconTile(app = app, size = iconSize)
                        Spacer(Modifier.height(5.dp))
                        Surface(
                            color = Color.Black.copy(alpha = 0.48f),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                text = app.label,
                                modifier = Modifier.padding(
                                    horizontal = 5.dp,
                                    vertical = 2.dp,
                                ),
                                color = Color.White,
                                fontSize = 10.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppIconTile(
    app: DesktopApp,
    size: Int,
    active: Boolean = false,
) {
    Surface(
        modifier = Modifier
            .size(size.dp)
            .semantics { contentDescription = app.label },
        shape = RoundedCornerShape((size * 0.24f).dp),
        color = Color(app.accentArgb),
        shadowElevation = if (active) 8.dp else 3.dp,
        tonalElevation = if (active) 7.dp else 1.dp,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding((size * 0.20f).dp),
        ) {
            val white = Color.White
            val line = (this.size.minDimension * 0.09f).coerceAtLeast(1f)
            val stroke = Stroke(width = line, cap = StrokeCap.Round)
            val w = this.size.width
            val h = this.size.height
            val center = Offset(w / 2f, h / 2f)

            when (app) {
                DesktopApp.BROWSER -> {
                    val radius = this.size.minDimension * 0.38f
                    drawCircle(
                        color = white,
                        radius = radius,
                        center = center,
                        style = stroke,
                    )
                    drawLine(
                        white,
                        Offset(center.x - radius, center.y),
                        Offset(center.x + radius, center.y),
                        line,
                        StrokeCap.Round,
                    )
                    drawArc(
                        color = white,
                        startAngle = 75f,
                        sweepAngle = 210f,
                        useCenter = false,
                        topLeft = Offset(
                            center.x - radius * 0.55f,
                            center.y - radius,
                        ),
                        size = Size(radius * 1.10f, radius * 2f),
                        style = stroke,
                    )
                    drawArc(
                        color = white,
                        startAngle = 255f,
                        sweepAngle = 210f,
                        useCenter = false,
                        topLeft = Offset(
                            center.x - radius * 0.55f,
                            center.y - radius,
                        ),
                        size = Size(radius * 1.10f, radius * 2f),
                        style = stroke,
                    )
                }

                DesktopApp.FILES -> {
                    val top = h * 0.27f
                    drawRoundRect(
                        color = white,
                        topLeft = Offset(w * 0.08f, top),
                        size = Size(w * 0.84f, h * 0.58f),
                        cornerRadius =
                            androidx.compose.ui.geometry.CornerRadius(
                                w * 0.09f,
                                w * 0.09f,
                            ),
                        style = stroke,
                    )
                    drawLine(
                        white,
                        Offset(w * 0.15f, top),
                        Offset(w * 0.39f, h * 0.13f),
                        line,
                        StrokeCap.Round,
                    )
                    drawLine(
                        white,
                        Offset(w * 0.39f, h * 0.13f),
                        Offset(w * 0.58f, h * 0.27f),
                        line,
                        StrokeCap.Round,
                    )
                }

                DesktopApp.TERMINAL -> {
                    drawLine(
                        white,
                        Offset(w * 0.14f, h * 0.25f),
                        Offset(w * 0.43f, h * 0.50f),
                        line,
                        StrokeCap.Round,
                    )
                    drawLine(
                        white,
                        Offset(w * 0.43f, h * 0.50f),
                        Offset(w * 0.14f, h * 0.75f),
                        line,
                        StrokeCap.Round,
                    )
                    drawLine(
                        white,
                        Offset(w * 0.50f, h * 0.75f),
                        Offset(w * 0.84f, h * 0.75f),
                        line,
                        StrokeCap.Round,
                    )
                }

                DesktopApp.APPS -> {
                    val cell = w * 0.25f
                    val gap = w * 0.12f
                    val startX = (w - cell * 2f - gap) / 2f
                    val startY = (h - cell * 2f - gap) / 2f
                    repeat(2) { row ->
                        repeat(2) { column ->
                            drawRoundRect(
                                color = white,
                                topLeft = Offset(
                                    startX + column * (cell + gap),
                                    startY + row * (cell + gap),
                                ),
                                size = Size(cell, cell),
                                cornerRadius =
                                    androidx.compose.ui.geometry.CornerRadius(
                                        cell * 0.23f,
                                        cell * 0.23f,
                                    ),
                            )
                        }
                    }
                }

                DesktopApp.DOWNLOADS -> {
                    drawLine(
                        white,
                        Offset(center.x, h * 0.12f),
                        Offset(center.x, h * 0.63f),
                        line,
                        StrokeCap.Round,
                    )
                    drawLine(
                        white,
                        Offset(center.x, h * 0.63f),
                        Offset(w * 0.27f, h * 0.43f),
                        line,
                        StrokeCap.Round,
                    )
                    drawLine(
                        white,
                        Offset(center.x, h * 0.63f),
                        Offset(w * 0.73f, h * 0.43f),
                        line,
                        StrokeCap.Round,
                    )
                    drawLine(
                        white,
                        Offset(w * 0.18f, h * 0.84f),
                        Offset(w * 0.82f, h * 0.84f),
                        line,
                        StrokeCap.Round,
                    )
                }

                DesktopApp.STORE -> {
                    drawRoundRect(
                        color = white,
                        topLeft = Offset(w * 0.18f, h * 0.30f),
                        size = Size(w * 0.64f, h * 0.55f),
                        cornerRadius =
                            androidx.compose.ui.geometry.CornerRadius(
                                w * 0.07f,
                                w * 0.07f,
                            ),
                        style = stroke,
                    )
                    drawArc(
                        color = white,
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(w * 0.31f, h * 0.12f),
                        size = Size(w * 0.38f, h * 0.36f),
                        style = stroke,
                    )
                }

                DesktopApp.CONTROL_CENTER -> {
                    val rows = listOf(0.25f, 0.50f, 0.75f)
                    val knobs = listOf(0.68f, 0.34f, 0.58f)
                    rows.forEachIndexed { index, row ->
                        drawLine(
                            white,
                            Offset(w * 0.14f, h * row),
                            Offset(w * 0.86f, h * row),
                            line,
                            StrokeCap.Round,
                        )
                        drawCircle(
                            color = Color(app.accentArgb),
                            radius = w * 0.12f,
                            center = Offset(
                                w * knobs[index],
                                h * row,
                            ),
                        )
                        drawCircle(
                            color = white,
                            radius = w * 0.08f,
                            center = Offset(
                                w * knobs[index],
                                h * row,
                            ),
                            style = stroke,
                        )
                    }
                }

                DesktopApp.DISPLAYS -> {
                    drawRoundRect(
                        color = white,
                        topLeft = Offset(w * 0.08f, h * 0.12f),
                        size = Size(w * 0.84f, h * 0.58f),
                        cornerRadius =
                            androidx.compose.ui.geometry.CornerRadius(
                                w * 0.06f,
                                w * 0.06f,
                            ),
                        style = stroke,
                    )
                    drawLine(
                        white,
                        Offset(center.x, h * 0.70f),
                        Offset(center.x, h * 0.84f),
                        line,
                        StrokeCap.Round,
                    )
                    drawLine(
                        white,
                        Offset(w * 0.32f, h * 0.86f),
                        Offset(w * 0.68f, h * 0.86f),
                        line,
                        StrokeCap.Round,
                    )
                }

                DesktopApp.PERSONALIZATION -> {
                    drawRoundRect(
                        color = white,
                        topLeft = Offset(w * 0.08f, h * 0.12f),
                        size = Size(w * 0.84f, h * 0.74f),
                        cornerRadius =
                            androidx.compose.ui.geometry.CornerRadius(
                                w * 0.07f,
                                w * 0.07f,
                            ),
                        style = stroke,
                    )
                    drawCircle(
                        color = white,
                        radius = w * 0.08f,
                        center = Offset(w * 0.70f, h * 0.32f),
                    )
                    drawLine(
                        white,
                        Offset(w * 0.16f, h * 0.76f),
                        Offset(w * 0.42f, h * 0.48f),
                        line,
                        StrokeCap.Round,
                    )
                    drawLine(
                        white,
                        Offset(w * 0.42f, h * 0.48f),
                        Offset(w * 0.62f, h * 0.68f),
                        line,
                        StrokeCap.Round,
                    )
                    drawLine(
                        white,
                        Offset(w * 0.62f, h * 0.68f),
                        Offset(w * 0.79f, h * 0.53f),
                        line,
                        StrokeCap.Round,
                    )
                }

                DesktopApp.RUNTIMES -> {
                    drawCircle(
                        color = white,
                        radius = w * 0.34f,
                        center = center,
                        style = stroke,
                    )
                    drawCircle(
                        color = white,
                        radius = w * 0.11f,
                        center = center,
                    )
                    repeat(3) { index ->
                        val angle =
                            Math.toRadians((index * 120.0) - 90.0)
                        val end = Offset(
                            center.x +
                                kotlin.math.cos(angle).toFloat() * w * 0.34f,
                            center.y +
                                kotlin.math.sin(angle).toFloat() * h * 0.34f,
                        )
                        drawLine(
                            white,
                            center,
                            end,
                            line,
                            StrokeCap.Round,
                        )
                    }
                }

                DesktopApp.SYSTEM -> {
                    val radius = w * 0.25f
                    drawCircle(
                        color = white,
                        radius = radius,
                        center = center,
                        style = stroke,
                    )
                    drawCircle(
                        color = white,
                        radius = w * 0.07f,
                        center = center,
                    )
                    repeat(8) { index ->
                        val angle = Math.toRadians(index * 45.0)
                        val inner = Offset(
                            center.x +
                                kotlin.math.cos(angle).toFloat() * radius,
                            center.y +
                                kotlin.math.sin(angle).toFloat() * radius,
                        )
                        val outer = Offset(
                            center.x +
                                kotlin.math.cos(angle).toFloat() * w * 0.42f,
                            center.y +
                                kotlin.math.sin(angle).toFloat() * h * 0.42f,
                        )
                        drawLine(
                            white,
                            inner,
                            outer,
                            line,
                            StrokeCap.Round,
                        )
                    }
                }

                DesktopApp.PERFORMANCE -> {
                    drawArc(
                        color = white,
                        startAngle = 200f,
                        sweepAngle = 140f,
                        useCenter = false,
                        topLeft = Offset(w * 0.12f, h * 0.18f),
                        size = Size(w * 0.76f, h * 0.76f),
                        style = stroke,
                    )
                    drawLine(
                        white,
                        Offset(center.x, h * 0.65f),
                        Offset(w * 0.73f, h * 0.34f),
                        line,
                        StrokeCap.Round,
                    )
                    drawCircle(
                        color = white,
                        radius = w * 0.07f,
                        center = Offset(center.x, h * 0.65f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PocketPcStartLogo(
    size: Int,
) {
    val logoColor =
        MaterialTheme.colorScheme.onPrimaryContainer

    Surface(
        modifier = Modifier.size(size.dp),
        shape = RoundedCornerShape(
            (size * 0.28f).dp
        ),
        color =
            MaterialTheme.colorScheme.primaryContainer,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding((size * 0.25f).dp),
        ) {
            val gap = this.size.minDimension * 0.12f
            val tile =
                (this.size.minDimension - gap) / 2f
            val color = logoColor

            drawRoundRect(
                color = color,
                topLeft = Offset.Zero,
                size = Size(tile, tile),
                cornerRadius =
                    androidx.compose.ui.geometry
                        .CornerRadius(tile * 0.16f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(tile + gap, 0f),
                size = Size(tile, tile),
                cornerRadius =
                    androidx.compose.ui.geometry
                        .CornerRadius(tile * 0.16f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(0f, tile + gap),
                size = Size(tile, tile),
                cornerRadius =
                    androidx.compose.ui.geometry
                        .CornerRadius(tile * 0.16f),
            )
            drawRoundRect(
                color = color,
                topLeft =
                    Offset(tile + gap, tile + gap),
                size = Size(tile, tile),
                cornerRadius =
                    androidx.compose.ui.geometry
                        .CornerRadius(tile * 0.16f),
            )
        }
    }
}

@Composable
private fun PocketPcStartButton(
    active: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(44.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        tonalElevation = if (active) 8.dp else 2.dp,
        color =
            if (active) {
                MaterialTheme.colorScheme
                    .secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
    ) {
        Box(
            contentAlignment = Alignment.Center,
        ) {
            PocketPcStartLogo(size = 30)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskbarV2(
    desktop: DesktopController,
    peripherals: PeripheralSnapshot,
    updateAttention: PocketPcUpdateAttention =
        PocketPcUpdateAttention.NONE,
    onUpdateClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val compactMobile =
        configuration.screenWidthDp < 700 ||
            configuration.screenHeightDp < 500
    val taskbarHeight = if (compactMobile) 52.dp else 58.dp
    val taskIconSize = if (compactMobile) 32 else 36

    val apps = remember(desktop.pinnedApps.toList(), desktop.windows.toList()) {
        (desktop.pinnedApps + desktop.windows.map { it.app }).distinct()
    }
    val activeApp = desktop.activeWindow?.app
    var taskbarMenuTarget by remember {
        mutableStateOf<DesktopApp?>(null)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(taskbarHeight),
        tonalElevation = 12.dp,
        shadowElevation = 12.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = if (compactMobile) 5.dp else 10.dp,
                    vertical = 4.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PocketPcStartButton(
                active = desktop.startMenuOpen,
                onClick = desktop::toggleStartMenu,
            )

            Spacer(Modifier.width(if (compactMobile) 4.dp else 8.dp))

            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                apps.forEach { app ->
                    val active = activeApp == app
                    val window = desktop.windows.firstOrNull { it.app == app }
                    val hoverSource = remember(app) { MutableInteractionSource() }
                    val hovered by hoverSource.collectIsHoveredAsState()
                    val focused by hoverSource.collectIsFocusedAsState()

                    Box {
                        Column(
                            horizontalAlignment =
                                Alignment.CenterHorizontally,
                            modifier = Modifier
                                .pointerHoverIcon(
                                    PointerIcon.Hand
                                )
                                .hoverable(hoverSource)
                                .focusable(
                                    interactionSource =
                                        hoverSource
                                )
                                .border(
                                    width =
                                        if (focused) {
                                            2.dp
                                        } else {
                                            0.dp
                                        },
                                    color =
                                        if (focused) {
                                            Color.White.copy(
                                                alpha = 0.85f
                                            )
                                        } else {
                                            Color.Transparent
                                        },
                                    shape =
                                        RoundedCornerShape(
                                            10.dp
                                        ),
                                )
                                .background(
                                    if (
                                        hovered ||
                                        focused
                                    ) {
                                        Color.White.copy(
                                            alpha = 0.10f
                                        )
                                    } else {
                                        Color.Transparent
                                    },
                                    RoundedCornerShape(
                                        10.dp
                                    ),
                                )
                                .desktopSecondaryClick {
                                    desktop
                                        .closeContextMenu()
                                    taskbarMenuTarget =
                                        app
                                }
                                .combinedClickable(
                                    onClick = {
                                        taskbarMenuTarget =
                                            null
                                        desktop.open(app)
                                    },
                                    onLongClick = {
                                        desktop
                                            .closeContextMenu()
                                        taskbarMenuTarget =
                                            app
                                    },
                                )
                                .padding(
                                    horizontal = 3.dp
                                ),
                        ) {
                            AppIconTile(
                                app = app,
                                size = taskIconSize,
                                active = active,
                            )
                            Box(
                                Modifier
                                    .padding(top = 2.dp)
                                    .width(
                                        if (active) {
                                            18.dp
                                        } else {
                                            7.dp
                                        }
                                    )
                                    .height(2.dp)
                                    .background(
                                        when {
                                            active ->
                                                Color(
                                                    0xFF7EC8FF
                                                )
                                            window != null ->
                                                Color(
                                                    0xFF8D939C
                                                )
                                            else ->
                                                Color.Transparent
                                        },
                                        RoundedCornerShape(
                                            2.dp
                                        ),
                                    )
                            )
                        }

                        TaskbarAppMenu(
                            app = app,
                            window = window,
                            desktop = desktop,
                            expanded =
                                taskbarMenuTarget ==
                                    app,
                            onDismiss = {
                                taskbarMenuTarget =
                                    null
                            },
                        )
                    }
                }
            }

            if (
                updateAttention !=
                    PocketPcUpdateAttention.NONE
            ) {
                UpdateAttentionChip(
                    state = updateAttention,
                    onClick = onUpdateClick,
                )
                Spacer(Modifier.width(6.dp))
            }

            DesktopSystemTray(
                peripherals = peripherals,
                onClick = {
                    desktop.open(
                        DesktopApp.CONTROL_CENTER
                    )
                },
            )
        }
    }
}

@Composable
private fun TaskbarAppMenu(
    app: DesktopApp,
    window: dev.pocketpc.core.desktop.DesktopWindow?,
    desktop: DesktopController,
    expanded: Boolean,
    onDismiss: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        Text(
            app.label,
            modifier = Modifier.padding(
                horizontal = 12.dp,
                vertical = 7.dp,
            ),
            style =
                MaterialTheme.typography
                    .labelLarge,
        )

        DropdownMenuItem(
            text = {
                Text(
                    if (window?.minimized == true) {
                        "Restaurar janela"
                    } else {
                        "Abrir / trazer para frente"
                    }
                )
            },
            onClick = {
                desktop.open(app)
                onDismiss()
            },
        )

        if (window != null) {
            DropdownMenuItem(
                text = {
                    Text("Minimizar")
                },
                onClick = {
                    desktop.minimize(window.id)
                    onDismiss()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        if (window.maximized) {
                            "Restaurar tamanho"
                        } else {
                            "Maximizar"
                        }
                    )
                },
                onClick = {
                    desktop.toggleMaximize(
                        window.id
                    )
                    onDismiss()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        "Fechar janela",
                        color =
                            MaterialTheme
                                .colorScheme.error,
                    )
                },
                onClick = {
                    desktop.close(window.id)
                    onDismiss()
                },
            )
            HorizontalDivider()
        }

        DropdownMenuItem(
            text = {
                Text(
                    if (
                        app in
                        desktop.pinnedApps
                    ) {
                        "Desafixar da barra de tarefas"
                    } else {
                        "Fixar na barra de tarefas"
                    }
                )
            },
            onClick = {
                desktop.togglePin(app)
                onDismiss()
            },
        )
    }
}

@Composable
private fun UpdateAttentionChip(
    state: PocketPcUpdateAttention,
    onClick: () -> Unit,
) {
    val label =
        when (state) {
            PocketPcUpdateAttention.NONE -> ""
            PocketPcUpdateAttention.AVAILABLE -> "UPD"
            PocketPcUpdateAttention.DOWNLOADING -> "↓"
            PocketPcUpdateAttention.READY -> "UPD ✓"
            PocketPcUpdateAttention.BLOCKED -> "UPD !"
        }

    val container =
        when (state) {
            PocketPcUpdateAttention.READY ->
                MaterialTheme.colorScheme
                    .primaryContainer
            PocketPcUpdateAttention.BLOCKED ->
                MaterialTheme.colorScheme
                    .errorContainer
            else ->
                MaterialTheme.colorScheme
                    .secondaryContainer
        }

    val content =
        when (state) {
            PocketPcUpdateAttention.READY ->
                MaterialTheme.colorScheme
                    .onPrimaryContainer
            PocketPcUpdateAttention.BLOCKED ->
                MaterialTheme.colorScheme
                    .onErrorContainer
            else ->
                MaterialTheme.colorScheme
                    .onSecondaryContainer
        }

    Surface(
        modifier = Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = container,
        tonalElevation = 3.dp,
    ) {
        Text(
            label,
            modifier = Modifier.padding(
                horizontal = 8.dp,
                vertical = 6.dp,
            ),
            color = content,
            fontSize = 8.sp,
        )
    }
}

@Composable
private fun DesktopSystemTray(
    peripherals: PeripheralSnapshot,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val compactMobile =
        configuration.screenWidthDp < 700 ||
            configuration.screenHeightDp < 500
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var battery by remember { mutableIntStateOf(readBatteryPercent(context)) }
    var online by remember { mutableStateOf(isOnline(context)) }

    LaunchedEffect(Unit) {
        var ticks = 0
        while (true) {
            now = LocalDateTime.now()
            if (ticks % 10 == 0) {
                battery = readBatteryPercent(context)
                online = isOnline(context)
            }
            ticks++
            delay(1_000)
        }
    }

    Surface(
        modifier = Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compactMobile) 5.dp else 9.dp,
                vertical = 4.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement =
                Arrangement.spacedBy(
                    if (compactMobile) 4.dp else 7.dp
                ),
        ) {
            if (
                peripherals.desktopInputActive &&
                !compactMobile
            ) {
                Text(
                    "INPUT",
                    fontSize = 8.sp,
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant,
                )
            }
            Text(
                if (online) "●" else "○",
                fontSize = 9.sp,
                color =
                    if (online) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
            )
            Text(
                "${battery.coerceIn(0, 100)}%",
                fontSize = 9.sp,
            )
            if (compactMobile) {
                Text(
                    now.format(
                        DateTimeFormatter.ofPattern("HH:mm")
                    ),
                    fontSize = 10.sp,
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        now.format(
                            DateTimeFormatter.ofPattern("HH:mm")
                        ),
                        fontSize = 11.sp,
                    )
                    Text(
                        now.format(
                            DateTimeFormatter.ofPattern("dd/MM")
                        ),
                        fontSize = 8.sp,
                        color =
                            MaterialTheme.colorScheme
                                .onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun StartMenuV2(
    desktop: DesktopController,
    peripherals: PeripheralSnapshot,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val compactMobile =
        configuration.screenWidthDp < 700 ||
            configuration.screenHeightDp < 500
    val menuWidth =
        (configuration.screenWidthDp - 16)
            .coerceIn(280, 390)
            .dp
    val menuHeight =
        (configuration.screenHeightDp - 76)
            .coerceIn(220, 350)
            .dp
    val appColumns =
        if (configuration.screenWidthDp < 380) 3 else 4

    var query by remember { mutableStateOf("") }
    val visibleApps = remember(query) {
        val normalized = query.trim()
        if (normalized.isBlank()) {
            DesktopApp.entries
        } else {
            DesktopApp.entries.filter { app ->
                app.label.contains(
                    normalized,
                    ignoreCase = true,
                ) ||
                    app.name.contains(
                        normalized,
                        ignoreCase = true,
                    )
            }
        }
    }

    Surface(
        modifier = modifier
            .width(menuWidth)
            .heightIn(max = menuHeight),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 14.dp,
        shadowElevation = 18.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement =
                Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically,
            ) {
                PocketPcStartLogo(size = 34)

                Spacer(Modifier.width(9.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        "PocketPC",
                        style =
                            MaterialTheme.typography
                                .titleLarge,
                    )
                    Text(
                        BuildConfig.VERSION_NAME,
                        fontSize = 8.sp,
                        color =
                            MaterialTheme.colorScheme
                                .onSurfaceVariant,
                    )
                }

                Text(
                    if (peripherals.desktopInputActive) {
                        "Desktop input"
                    } else {
                        "Touch"
                    },
                    fontSize = 8.sp,
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                singleLine = true,
                placeholder = {
                    Text("Pesquisar aplicativos")
                },
                textStyle =
                    LocalTextStyle.current.copy(
                        fontSize = 11.sp
                    ),
            )

            HorizontalDivider()

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(
                        rememberScrollState()
                    ),
                verticalArrangement =
                    Arrangement.spacedBy(5.dp),
            ) {
                visibleApps.chunked(appColumns)
                    .forEach { rowApps ->
                        Row(
                            modifier =
                                Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                Arrangement.spacedBy(5.dp),
                        ) {
                            rowApps.forEach { app ->
                                TextButton(
                                    onClick = {
                                        desktop.open(app)
                                    },
                                    modifier =
                                        Modifier.weight(1f),
                                    contentPadding =
                                        PaddingValues(
                                            horizontal = 3.dp,
                                            vertical = 4.dp,
                                        ),
                                ) {
                                    Column(
                                        horizontalAlignment =
                                            Alignment.CenterHorizontally,
                                    ) {
                                        AppIconTile(
                                            app = app,
                                            size = 32,
                                        )
                                        Spacer(
                                            Modifier.height(3.dp)
                                        )
                                        Text(
                                            app.label,
                                            fontSize = 8.sp,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }

                            repeat(appColumns - rowApps.size) {
                                Spacer(
                                    Modifier.weight(1f)
                                )
                            }
                        }
                    }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.SpaceBetween,
            ) {
                Text(
                    if (compactMobile) {
                        "Toque e segure para opções"
                    } else {
                        "Meta+E Arquivos • Meta+B Web • Alt+Tab"
                    },
                    fontSize = 8.sp,
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant,
                )
                TextButton(
                    onClick = desktop::minimizeAll,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(
                        "Mostrar desktop",
                        fontSize = 8.sp,
                    )
                }
            }
        }
    }
}

@Composable
fun DesktopContextMenu(
    desktop: DesktopController,
    modifier: Modifier = Modifier,
) {
    val target = desktop.contextMenuTarget
    val targetWindow =
        target?.let { app ->
            desktop.windows.firstOrNull {
                it.app == app
            }
        }

    Surface(
        modifier = modifier.width(270.dp),
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 15.dp,
        shadowElevation = 18.dp,
    ) {
        Column(Modifier.padding(8.dp)) {
            if (target != null) {
                Text(
                    target.label,
                    modifier = Modifier.padding(10.dp),
                    fontSize = 12.sp,
                )
                TextButton(
                    onClick = {
                        desktop.open(target)
                        desktop.closeContextMenu()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (targetWindow?.minimized == true) {
                            "Restaurar janela"
                        } else {
                            "Abrir / trazer para frente"
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (targetWindow != null) {
                    TextButton(
                        onClick = {
                            desktop.minimize(targetWindow.id)
                            desktop.closeContextMenu()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Minimizar",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    TextButton(
                        onClick = {
                            desktop.toggleMaximize(targetWindow.id)
                            desktop.closeContextMenu()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (targetWindow.maximized) {
                                "Restaurar tamanho"
                            } else {
                                "Maximizar"
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    TextButton(
                        onClick = {
                            desktop.close(targetWindow.id)
                            desktop.closeContextMenu()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Fechar janela",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    HorizontalDivider()
                }

                TextButton(
                    onClick = {
                        desktop.togglePin(target)
                        desktop.closeContextMenu()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (target in desktop.pinnedApps) {
                            "Desafixar da barra de tarefas"
                        } else {
                            "Fixar na barra de tarefas"
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Text("Área de trabalho", modifier = Modifier.padding(10.dp))
            }

            HorizontalDivider()
            TextButton(
                onClick = { desktop.open(DesktopApp.PERSONALIZATION) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Personalização", modifier = Modifier.fillMaxWidth())
            }
            TextButton(
                onClick = { desktop.open(DesktopApp.SYSTEM) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Este PC", modifier = Modifier.fillMaxWidth())
            }
            TextButton(
                onClick = desktop::minimizeAll,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Mostrar área de trabalho", modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

internal fun Modifier.desktopSecondaryClick(
    onSecondaryClick: () -> Unit,
): Modifier =
    pointerInput(onSecondaryClick) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (
                    event.type == PointerEventType.Press &&
                    event.buttons.isSecondaryPressed
                ) {
                    event.changes.forEach { change -> change.consume() }
                    onSecondaryClick()
                }
            }
        }
    }

private fun readBatteryPercent(context: Context): Int {
    val battery = context.registerReceiver(
        null,
        IntentFilter(Intent.ACTION_BATTERY_CHANGED),
    ) ?: return 0

    val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return 0
    return ((level * 100f) / scale).toInt()
}

private fun isOnline(context: Context): Boolean {
    val manager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
