package dev.pocketpc.core

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketpc.core.desktop.DesktopApp
import dev.pocketpc.core.desktop.DesktopController
import dev.pocketpc.core.desktop.DesktopWindow
import dev.pocketpc.core.feature.files.FilesPane
import dev.pocketpc.core.feature.terminal.TerminalPane
import dev.pocketpc.core.telemetry.TelemetryMonitor
import dev.pocketpc.core.telemetry.TelemetrySample
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PocketPcApp()
            }
        }
    }
}

@Composable
private fun PocketPcApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val desktop = remember { DesktopController() }
    val telemetry = remember { TelemetryMonitor(context.applicationContext) }
    val sample by telemetry.sample.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        telemetry.start(scope)
        onDispose { telemetry.stop() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        DesktopIcons(desktop)

        desktop.windows
            .filterNot { it.minimized }
            .sortedBy { it.zIndex }
            .forEach { window ->
                DesktopWindowView(
                    window = window,
                    sample = sample,
                    desktop = desktop,
                )
            }

        PerformanceHud(
            sample,
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 34.dp, end = 12.dp),
        )

        if (desktop.startMenuOpen) {
            StartMenu(
                desktop,
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 64.dp),
            )
        }

        Taskbar(desktop, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun DesktopIcons(desktop: DesktopController) {
    Column(
        Modifier.padding(start = 18.dp, top = 52.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DesktopApp.entries.forEach { app ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(76.dp)
                    .clickable { desktop.open(app) },
            ) {
                Text(app.glyph, fontSize = 26.sp)
                Text(app.label, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun Taskbar(
    desktop: DesktopController,
    modifier: Modifier = Modifier,
) {
    Surface(
        tonalElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = desktop::toggleStartMenu,
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) {
                Text("PC")
            }

            desktop.windows.forEach { window ->
                AssistChip(
                    onClick = { desktop.open(window.app) },
                    label = { Text(window.title) },
                )
            }

            Spacer(Modifier.weight(1f))
            Text("PocketPC 0.1-B", fontSize = 12.sp)
        }
    }
}

@Composable
private fun StartMenu(
    desktop: DesktopController,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.width(280.dp),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 12.dp,
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("PocketPC", style = MaterialTheme.typography.titleLarge)
            Text("Alpha desktop shell", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))

            DesktopApp.entries.forEach { app ->
                TextButton(
                    onClick = { desktop.open(app) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "${app.glyph}  ${app.label}",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopWindowView(
    window: DesktopWindow,
    sample: TelemetrySample,
    desktop: DesktopController,
) {
    var x by remember(window.id) {
        mutableFloatStateOf(130f + (window.zIndex % 3) * 35f)
    }
    var y by remember(window.id) {
        mutableFloatStateOf(90f + (window.zIndex % 3) * 24f)
    }

    val windowModifier = if (window.maximized) {
        Modifier
            .fillMaxSize()
            .padding(bottom = 56.dp)
    } else {
        Modifier
            .widthIn(min = 300.dp, max = 720.dp)
            .heightIn(min = 250.dp, max = 520.dp)
            .fillMaxWidth(0.72f)
            .fillMaxHeight(0.62f)
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
    }

    Surface(
        modifier = windowModifier.clickable(
            indication = null,
            interactionSource = remember {
                androidx.compose.foundation.interaction.MutableInteractionSource()
            },
        ) {
            desktop.focus(window.id)
        },
        shape = RoundedCornerShape(if (window.maximized) 0.dp else 14.dp),
        shadowElevation = 14.dp,
        tonalElevation = 5.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(window.id, window.maximized) {
                        if (!window.maximized) {
                            detectDragGestures(
                                onDragStart = { desktop.focus(window.id) },
                            ) { change, drag ->
                                change.consume()
                                x += drag.x
                                y += drag.y
                            }
                        }
                    }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(window.title, modifier = Modifier.weight(1f))
                TextButton(onClick = { desktop.minimize(window.id) }) {
                    Text("—")
                }
                TextButton(onClick = { desktop.toggleMaximize(window.id) }) {
                    Text(if (window.maximized) "▣" else "□")
                }
                TextButton(onClick = { desktop.close(window.id) }) {
                    Text("×")
                }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                when (window.app) {
                    DesktopApp.FILES -> FilesPane()
                    DesktopApp.TERMINAL -> TerminalPane()
                    DesktopApp.SYSTEM -> SystemPane()
                    DesktopApp.PERFORMANCE -> PerformancePane(sample)
                }
            }
        }
    }
}

@Composable
private fun SystemPane() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sistema", style = MaterialTheme.typography.titleMedium)
        Text("Desktop shell: IMPLEMENTED")
        Text("Janelas: IMPLEMENTED")
        Text("Arquivos SAF: IMPLEMENTED")
        Text("Terminal sandbox: IMPLEMENTED (não-PTY)")
        Text("Telemetry: IMPLEMENTED, app-level")
        Text("Linux ARM rootfs: PLANNED")
        Text("Windows compatibility: PLANNED")
        Text("vGPU/Vulkan bridge: PLANNED")
    }
}

@Composable
private fun PerformancePane(sample: TelemetrySample) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Desempenho", style = MaterialTheme.typography.titleMedium)
        Metric("FPS da UI", sample.fps.toString())
        Metric("CPU do processo*", "${sample.processCpuPercent}%")
        Metric("RAM do processo", "${sample.processRamMb} MB")
        Metric("RAM disponível", "${sample.availableRamMb} MB")
        Metric(
            "Thermal headroom",
            sample.thermalHeadroom?.let { "%.2f".format(it) } ?: "indisponível",
        )
        Text(
            "*Amostra simples do processo; não representa CPU total do aparelho.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Text(value)
    }
}

@Composable
private fun PerformanceHud(
    sample: TelemetrySample,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 8.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("${sample.fps} FPS", fontSize = 14.sp)
            Text("RAM ${sample.processRamMb} MB", fontSize = 10.sp)
            sample.thermalHeadroom?.let {
                Text("TH %.2f".format(it), fontSize = 10.sp)
            }
        }
    }
}
