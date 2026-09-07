package dev.pocketpc.core.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketpc.core.BuildConfig
import dev.pocketpc.core.desktop.DesktopApp
import dev.pocketpc.core.desktop.DesktopController
import dev.pocketpc.core.desktop.DesktopWindow
import dev.pocketpc.core.runtime.ExecutionSubstrateProbe
import dev.pocketpc.core.runtime.NativeRuntimeHost
import dev.pocketpc.core.runtime.RootfsLinkManager
import dev.pocketpc.core.runtime.RuntimeInstallManager
import dev.pocketpc.core.runtime.RuntimePackageManager
import dev.pocketpc.core.storage.StorageRepository
import dev.pocketpc.core.system.collectSystemSnapshot
import dev.pocketpc.core.telemetry.TelemetryMonitor
import dev.pocketpc.core.terminal.LocalShellEngine
import kotlin.math.roundToInt

@Composable
fun PocketPcApp() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val desktop = remember { DesktopController() }
    val browserSession = remember { BrowserSessionState() }
    val telemetry = remember { TelemetryMonitor(appContext) }
    val storage = remember { StorageRepository(appContext) }
    val terminal = remember { LocalShellEngine(appContext) }
    val runtimes = remember { RuntimePackageManager(appContext) }
    val installer = remember { RuntimeInstallManager(appContext, runtimes) }
    val linkManager = remember { RootfsLinkManager() }
    val nativeHost = remember { NativeRuntimeHost.status(appContext) }
    val substrate = remember { ExecutionSubstrateProbe.inspect(appContext) }
    val systemSnapshot = remember { collectSystemSnapshot(appContext) }
    val sample by telemetry.sample.collectAsStateWithLifecycle()

    var storageRoot by rememberSaveable { mutableStateOf(storage.rootUriString) }
    var storagePickerError by rememberSaveable { mutableStateOf<String?>(null) }
    var runtimeManifestUri by rememberSaveable { mutableStateOf<String?>(null) }
    var runtimeRootfsUri by rememberSaveable { mutableStateOf<String?>(null) }

    fun persistRead(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            storage.persistRoot(uri)
                .onSuccess {
                    storageRoot = uri.toString()
                    storagePickerError = null
                }
                .onFailure {
                    storagePickerError = it.message ?: "Falha ao persistir a permissão da pasta."
                }
        }
    }

    val manifestPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            persistRead(uri)
            runtimeManifestUri = uri.toString()
        }
    }

    val rootfsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            persistRead(uri)
            runtimeRootfsUri = uri.toString()
        }
    }

    DisposableEffect(Unit) {
        telemetry.start(scope)
        onDispose { telemetry.stop() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        DesktopIcons(desktop)

        desktop.windows
            .filterNot { it.minimized }
            .sortedBy { it.zIndex }
            .forEach { window ->
                DesktopWindowView(window = window, desktop = desktop) {
                    when (window.app) {
                        DesktopApp.BROWSER -> BrowserApp(browserSession)
                        DesktopApp.FILES -> FilesApp(
                            repository = storage,
                            rootUri = storageRoot,
                            pickerError = storagePickerError,
                            onChooseStorage = { folderPicker.launch(storageRoot?.let(Uri::parse)) },
                            onDisconnectStorage = {
                                storage.clearRoot()
                                storageRoot = null
                                storagePickerError = null
                            },
                        )
                        DesktopApp.TERMINAL -> TerminalApp(terminal)
                        DesktopApp.RUNTIMES -> RuntimeApp(
                            manager = runtimes,
                            installer = installer,
                            linkManager = linkManager,
                            nativeHost = nativeHost,
                            substrate = substrate,
                            manifestUri = runtimeManifestUri,
                            rootfsUri = runtimeRootfsUri,
                            onChooseManifest = {
                                manifestPicker.launch(
                                    arrayOf("application/json", "text/plain", "application/octet-stream")
                                )
                            },
                            onChooseRootfs = { rootfsPicker.launch(arrayOf("*/*")) },
                            onClearSelection = {
                                listOfNotNull(runtimeManifestUri, runtimeRootfsUri).forEach { uriString ->
                                    runCatching {
                                        context.contentResolver.releasePersistableUriPermission(
                                            Uri.parse(uriString),
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                        )
                                    }
                                }
                                runtimeManifestUri = null
                                runtimeRootfsUri = null
                            },
                        )
                        DesktopApp.SYSTEM -> SystemApp(
                            snapshot = systemSnapshot,
                            storageConfigured = storageRoot != null,
                            nativeHost = nativeHost,
                            substrate = substrate,
                        )
                        DesktopApp.PERFORMANCE -> PerformanceApp(sample)
                    }
                }
            }

        PerformanceHud(
            sample = sample,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 34.dp, end = 12.dp),
        )

        if (desktop.startMenuOpen) {
            StartMenu(
                desktop = desktop,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 64.dp),
            )
        }

        Taskbar(desktop = desktop, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun DesktopIcons(desktop: DesktopController) {
    Column(
        modifier = Modifier.padding(start = 18.dp, top = 52.dp, bottom = 72.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DesktopApp.entries.forEach { app ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(82.dp)
                    .clickable { desktop.open(app) }
                    .padding(vertical = 3.dp),
            ) {
                Text(app.glyph, fontSize = 25.sp)
                Text(app.label, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Taskbar(desktop: DesktopController, modifier: Modifier = Modifier) {
    Surface(
        tonalElevation = 8.dp,
        modifier = modifier.fillMaxWidth().height(56.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = desktop::toggleStartMenu,
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) {
                Text("PC")
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                desktop.windows.sortedBy { it.zIndex }.forEach { window ->
                    AssistChip(
                        onClick = { desktop.open(window.app) },
                        label = { Text(if (window.minimized) "${window.title} ↓" else window.title) },
                    )
                }
            }

            Text(BuildConfig.VERSION_NAME.substringAfterLast("-"), fontSize = 12.sp)
        }
    }
}

@Composable
private fun StartMenu(desktop: DesktopController, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.width(300.dp),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 12.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("PocketPC", style = MaterialTheme.typography.titleLarge)
            Text(BuildConfig.VERSION_NAME + " • pocket desktop", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            DesktopApp.entries.forEach { app ->
                TextButton(
                    onClick = { desktop.open(app) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("${app.glyph}  ${app.label}", modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun DesktopWindowView(
    window: DesktopWindow,
    desktop: DesktopController,
    content: @Composable () -> Unit,
) {
    var x by remember(window.id) { mutableFloatStateOf(90f + (window.zIndex % 3) * 32f) }
    var y by remember(window.id) { mutableFloatStateOf(86f + (window.zIndex % 3) * 24f) }

    val windowModifier = if (window.maximized) {
        Modifier.fillMaxSize().padding(bottom = 56.dp)
    } else {
        Modifier
            .widthIn(min = 300.dp, max = 900.dp)
            .heightIn(min = 260.dp, max = 660.dp)
            .fillMaxWidth(0.82f)
            .fillMaxHeight(0.72f)
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
    }

    Surface(
        modifier = windowModifier.clickable(
            indication = null,
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        ) { desktop.focus(window.id) },
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
                            detectDragGestures(onDragStart = { desktop.focus(window.id) }) { change, drag ->
                                change.consume()
                                x = (x + drag.x).coerceAtLeast(0f)
                                y = (y + drag.y).coerceAtLeast(0f)
                            }
                        }
                    }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(window.title, modifier = Modifier.weight(1f))
                TextButton(onClick = { desktop.minimize(window.id) }) { Text("—") }
                TextButton(onClick = { desktop.toggleMaximize(window.id) }) {
                    Text(if (window.maximized) "▣" else "□")
                }
                TextButton(onClick = { desktop.close(window.id) }) { Text("×") }
            }

            Box(Modifier.fillMaxSize().padding(16.dp)) {
                content()
            }
        }
    }
}
