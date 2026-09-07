package dev.pocketpc.core.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.awaitPointerEventScope
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketpc.core.BuildConfig
import dev.pocketpc.core.desktop.DesktopApp
import dev.pocketpc.core.desktop.DesktopController
import dev.pocketpc.core.desktop.PeripheralSnapshot
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DesktopIconsV2(
    desktop: DesktopController,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.width(220.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DesktopApp.entries.chunked(2).forEach { rowApps ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowApps.forEach { app ->
                    val hoverSource = remember(app) { MutableInteractionSource() }
                    val hovered by hoverSource.collectIsHoveredAsState()

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(92.dp)
                            .hoverable(hoverSource)
                            .background(
                                if (hovered) {
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
                        AppIconTile(app = app, size = 48)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = app.label,
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
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
        modifier = Modifier.size(size.dp),
        shape = RoundedCornerShape((size * 0.24f).dp),
        color = Color(app.accentArgb),
        shadowElevation = if (active) 8.dp else 3.dp,
        tonalElevation = if (active) 7.dp else 1.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = app.glyph,
                color = Color.White,
                fontSize = (size * 0.25f).sp,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskbarV2(
    desktop: DesktopController,
    peripherals: PeripheralSnapshot,
    modifier: Modifier = Modifier,
) {
    val apps = remember(desktop.pinnedApps.toList(), desktop.windows.toList()) {
        (desktop.pinnedApps + desktop.windows.map { it.app }).distinct()
    }
    val activeApp = desktop.activeWindow?.app

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp),
        tonalElevation = 12.dp,
        shadowElevation = 12.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = desktop::toggleStartMenu,
                modifier = Modifier.size(46.dp),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("PC", fontSize = 12.sp)
            }

            Spacer(Modifier.width(8.dp))

            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                apps.forEach { app ->
                    val active = activeApp == app
                    val window = desktop.windows.firstOrNull { it.app == app }
                    val hoverSource = remember(app) { MutableInteractionSource() }
                    val hovered by hoverSource.collectIsHoveredAsState()

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .hoverable(hoverSource)
                            .background(
                                if (hovered) {
                                    Color.White.copy(alpha = 0.10f)
                                } else {
                                    Color.Transparent
                                },
                                RoundedCornerShape(10.dp),
                            )
                            .desktopSecondaryClick {
                                desktop.openContextMenu(app)
                            }
                            .combinedClickable(
                                onClick = { desktop.open(app) },
                                onLongClick = { desktop.openContextMenu(app) },
                            )
                            .padding(horizontal = 3.dp),
                    ) {
                        AppIconTile(app = app, size = 38, active = active)
                        Box(
                            Modifier
                                .padding(top = 2.dp)
                                .width(if (active) 18.dp else 7.dp)
                                .height(2.dp)
                                .background(
                                    when {
                                        active -> Color(0xFF7EC8FF)
                                        window != null -> Color(0xFF8D939C)
                                        else -> Color.Transparent
                                    },
                                    RoundedCornerShape(2.dp),
                                )
                        )
                    }
                }
            }

            DesktopSystemTray(peripherals)
        }
    }
}

@Composable
private fun DesktopSystemTray(peripherals: PeripheralSnapshot) {
    val context = LocalContext.current
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

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (peripherals.mouseCount > 0) Text("M", fontSize = 10.sp)
        if (peripherals.keyboardCount > 0) Text("K", fontSize = 10.sp)
        if (peripherals.gamepadCount > 0) Text("G", fontSize = 10.sp)
        Text(if (online) "NET" else "OFF", fontSize = 10.sp)
        Text("${battery.coerceIn(0, 100)}%", fontSize = 10.sp)
        Column(horizontalAlignment = Alignment.End) {
            Text(
                now.format(DateTimeFormatter.ofPattern("HH:mm")),
                fontSize = 12.sp,
            )
            Text(
                now.format(DateTimeFormatter.ofPattern("dd/MM")),
                fontSize = 9.sp,
            )
        }
    }
}

@Composable
fun StartMenuV2(
    desktop: DesktopController,
    peripherals: PeripheralSnapshot,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.width(360.dp),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 14.dp,
        shadowElevation = 16.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("PocketPC", fontSize = 22.sp)
            Text(BuildConfig.VERSION_NAME, fontSize = 11.sp)
            Text(
                if (peripherals.desktopInputActive) {
                    "Perfil desktop: mouse/teclado detectado"
                } else {
                    "Perfil touch desktop"
                },
                fontSize = 11.sp,
            )
            HorizontalDivider()

            DesktopApp.entries.chunked(3).forEach { rowApps ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowApps.forEach { app ->
                        TextButton(
                            onClick = { desktop.open(app) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                AppIconTile(app = app, size = 36)
                                Text(app.label, fontSize = 9.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            Text(
                "Atalhos: Meta+E Arquivos  |  Meta+B Navegador  |  " +
                    "Ctrl+Alt+T Terminal  |  Alt+Tab",
                fontSize = 9.sp,
            )
        }
    }
}

@Composable
fun DesktopContextMenu(
    desktop: DesktopController,
    modifier: Modifier = Modifier,
) {
    val target = desktop.contextMenuTarget

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
                    onClick = { desktop.open(target) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Abrir", modifier = Modifier.fillMaxWidth())
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
                Text("Area de trabalho", modifier = Modifier.padding(10.dp))
            }

            HorizontalDivider()
            TextButton(
                onClick = { desktop.open(DesktopApp.PERSONALIZATION) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Personalizacao", modifier = Modifier.fillMaxWidth())
            }
            TextButton(
                onClick = { desktop.open(DesktopApp.SYSTEM) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Sistema", modifier = Modifier.fillMaxWidth())
            }
            TextButton(
                onClick = desktop::minimizeAll,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Mostrar area de trabalho", modifier = Modifier.fillMaxWidth())
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
