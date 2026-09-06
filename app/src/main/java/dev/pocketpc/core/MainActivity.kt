package dev.pocketpc.core

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketpc.core.desktop.DesktopApp
import dev.pocketpc.core.desktop.DesktopController
import dev.pocketpc.core.desktop.DesktopWindow
import dev.pocketpc.core.telemetry.TelemetryMonitor
import dev.pocketpc.core.telemetry.TelemetrySample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val desktop = remember { DesktopController() }
    val telemetry = remember { TelemetryMonitor(context.applicationContext) }
    val sample by telemetry.sample.collectAsStateWithLifecycle()
    var storageRoot by rememberSaveable { mutableStateOf<String?>(null) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            storageRoot = uri.toString()
        }
    }

    DisposableEffect(Unit) {
        telemetry.start(scope)
        onDispose { telemetry.stop() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
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
                    storageRoot = storageRoot,
                    onChooseStorage = { folderPicker.launch(storageRoot?.let(Uri::parse)) },
                )
            }

        PerformanceHud(sample, Modifier.align(Alignment.TopEnd).padding(top = 34.dp, end = 12.dp))

        if (desktop.startMenuOpen) {
            StartMenu(desktop, Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 64.dp))
        }

        Taskbar(desktop, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun DesktopIcons(desktop: DesktopController) {
    Column(Modifier.padding(start = 18.dp, top = 52.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DesktopApp.entries.forEach { app ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(76.dp).clickable { desktop.open(app) }
            ) {
                Text(app.glyph, fontSize = 26.sp)
                Text(app.label, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun Taskbar(desktop: DesktopController, modifier: Modifier = Modifier) {
    Surface(
        tonalElevation = 8.dp,
        modifier = modifier.fillMaxWidth().height(56.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = desktop::toggleStartMenu, contentPadding = PaddingValues(horizontal = 14.dp)) {
                Text("PC")
            }
            desktop.windows.forEach { window ->
                AssistChip(
                    onClick = { desktop.open(window.app) },
                    label = { Text(window.title) }
                )
            }
            Spacer(Modifier.weight(1f))
            Text("PocketPC 0.1", fontSize = 12.sp)
        }
    }
}

@Composable
private fun StartMenu(desktop: DesktopController, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.width(280.dp),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 12.dp
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("PocketPC", style = MaterialTheme.typography.titleLarge)
            Text("Alpha desktop shell", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            DesktopApp.entries.forEach { app ->
                TextButton(onClick = { desktop.open(app) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${app.glyph}  ${app.label}", modifier = Modifier.fillMaxWidth())
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
    storageRoot: String?,
    onChooseStorage: () -> Unit,
) {
    var x by remember(window.id) { mutableFloatStateOf(130f + (window.zIndex % 3) * 35f) }
    var y by remember(window.id) { mutableFloatStateOf(90f + (window.zIndex % 3) * 24f) }

    val windowModifier = if (window.maximized) {
        Modifier.fillMaxSize().padding(bottom = 56.dp)
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
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        ) { desktop.focus(window.id) },
        shape = RoundedCornerShape(if (window.maximized) 0.dp else 14.dp),
        shadowElevation = 14.dp,
        tonalElevation = 5.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(window.id, window.maximized) {
                        if (!window.maximized) {
                            detectDragGestures(onDragStart = { desktop.focus(window.id) }) { change, drag ->
                                change.consume()
                                x += drag.x
                                y += drag.y
                            }
                        }
                    }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(window.title, modifier = Modifier.weight(1f))
                TextButton(onClick = { desktop.minimize(window.id) }) { Text("—") }
                TextButton(onClick = { desktop.toggleMaximize(window.id) }) { Text(if (window.maximized) "▣" else "□") }
                TextButton(onClick = { desktop.close(window.id) }) { Text("×") }
            }
            Box(Modifier.fillMaxSize().padding(16.dp)) {
                when (window.app) {
                    DesktopApp.FILES -> FilesApp(storageRoot, onChooseStorage)
                    DesktopApp.TERMINAL -> TerminalPrototype()
                    DesktopApp.SYSTEM -> SystemPrototype(storageRoot)
                    DesktopApp.PERFORMANCE -> PerformancePrototype(sample)
                }
            }
        }
    }
}

data class FileEntry(val name: String, val directory: Boolean, val size: Long)

@Composable
private fun FilesApp(storageRoot: String?, onChooseStorage: () -> Unit) {
    val context = LocalContext.current
    var entries by remember(storageRoot) { mutableStateOf<List<FileEntry>>(emptyList()) }
    var error by remember(storageRoot) { mutableStateOf<String?>(null) }
    var loading by remember(storageRoot) { mutableStateOf(false) }

    LaunchedEffect(storageRoot) {
        if (storageRoot == null) {
            entries = emptyList()
            error = null
            return@LaunchedEffect
        }
        loading = true
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val root = DocumentFile.fromTreeUri(context, Uri.parse(storageRoot))
                    ?: error("Não foi possível abrir a pasta autorizada.")
                root.listFiles()
                    .map { FileEntry(it.name ?: "(sem nome)", it.isDirectory, it.length()) }
                    .sortedWith(compareByDescending<FileEntry> { it.directory }.thenBy { it.name.lowercase() })
            }
        }
        result.onSuccess {
            entries = it
            error = null
        }.onFailure {
            entries = emptyList()
            error = it.message ?: "Falha ao ler a pasta."
        }
        loading = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Explorador", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(onClick = onChooseStorage) { Text(if (storageRoot == null) "Escolher pasta" else "Trocar pasta") }
        }

        if (storageRoot == null) {
            Text("Escolha uma pasta do Android para dar acesso persistente ao PocketPC.")
            Text("O app usa o Storage Access Framework; não exige acesso irrestrito ao armazenamento.", style = MaterialTheme.typography.bodySmall)
            return@Column
        }

        Text(storageRoot, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (!loading && error == null && entries.isEmpty()) {
                Text("Pasta vazia.", style = MaterialTheme.typography.bodySmall)
            }
            entries.forEach { entry ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (entry.directory) "▣" else "•", modifier = Modifier.width(28.dp))
                    Text(entry.name, modifier = Modifier.weight(1f))
                    if (!entry.directory) Text(formatBytes(entry.size), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

@Composable
private fun TerminalPrototype() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Pocket Terminal", style = MaterialTheme.typography.titleMedium)
        Text("pocket@android:~$", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        Text("Runtime Linux ainda NÃO está integrado nesta build.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SystemPrototype(storageRoot: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sistema", style = MaterialTheme.typography.titleMedium)
        Text("Shell: IMPLEMENTED")
        Text("Janelas: IMPLEMENTED")
        Text("Arquivos SAF: ${if (storageRoot != null) "CONFIGURED" else "IMPLEMENTED / aguardando pasta"}")
        Text("Telemetry: IMPLEMENTED, app-level")
        Text("Linux: PLANNED")
        Text("Windows compatibility: PLANNED")
        Text("vGPU: PLANNED")
    }
}

@Composable
private fun PerformancePrototype(sample: TelemetrySample) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Desempenho", style = MaterialTheme.typography.titleMedium)
        Metric("FPS da UI", sample.fps.toString())
        Metric("CPU do processo*", "${sample.processCpuPercent}%")
        Metric("RAM do processo", "${sample.processRamMb} MB")
        Metric("RAM disponível", "${sample.availableRamMb} MB")
        Metric("Thermal headroom", sample.thermalHeadroom?.let { "%.2f".format(it) } ?: "indisponível")
        Text("*Amostra simples do processo; não representa CPU total do aparelho.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value)
    }
}

@Composable
private fun PerformanceHud(sample: TelemetrySample, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), tonalElevation = 8.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("${sample.fps} FPS", fontSize = 14.sp)
            Text("RAM ${sample.processRamMb} MB", fontSize = 10.sp)
            sample.thermalHeadroom?.let { Text("TH %.2f".format(it), fontSize = 10.sp) }
        }
    }
}
