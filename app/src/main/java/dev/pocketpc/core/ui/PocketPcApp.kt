package dev.pocketpc.core.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketpc.core.desktop.DesktopApp
import dev.pocketpc.core.desktop.DesktopCommand
import dev.pocketpc.core.desktop.DesktopCapabilityMonitor
import dev.pocketpc.core.desktop.DesktopController
import dev.pocketpc.core.desktop.DesktopPeripheralMonitor
import dev.pocketpc.core.desktop.DesktopPinStore
import dev.pocketpc.core.desktop.DesktopWindow
import dev.pocketpc.core.desktop.WindowSnap
import dev.pocketpc.core.runtime.ExecutionSubstrateProbe
import dev.pocketpc.core.runtime.NativeRuntimeHost
import dev.pocketpc.core.runtime.RootfsLinkManager
import dev.pocketpc.core.runtime.RuntimeInstallManager
import dev.pocketpc.core.runtime.RuntimePackageManager
import dev.pocketpc.core.storage.StorageRepository
import dev.pocketpc.core.system.collectSystemSnapshot
import dev.pocketpc.core.telemetry.TelemetryMonitor
import dev.pocketpc.core.terminal.LocalShellEngine
import kotlinx.coroutines.flow.Flow
import kotlin.math.roundToInt

@Composable
fun PocketPcApp(commandFlow: Flow<DesktopCommand>) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val pinStore = remember { DesktopPinStore(appContext) }
    val desktop = remember {
        DesktopController(
            initialPinnedApps = pinStore.load(),
            onPinnedAppsChanged = pinStore::save,
        )
    }
    val browserSession = remember { BrowserSessionState() }
    val appearance = remember { DesktopAppearanceState(appContext) }
    val systemDark = isSystemInDarkTheme()
    val useDarkTheme =
        when (appearance.themeMode) {
            DesktopThemeMode.SYSTEM -> systemDark
            DesktopThemeMode.LIGHT -> false
            DesktopThemeMode.DARK -> true
        }
    val peripheralMonitor = remember { DesktopPeripheralMonitor(appContext) }
    val capabilityMonitor = remember { DesktopCapabilityMonitor(appContext) }
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
    val peripherals by peripheralMonitor.state.collectAsStateWithLifecycle()
    val desktopCapabilities by capabilityMonitor.state.collectAsStateWithLifecycle()

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

    val wallpaperPicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                persistRead(uri)
                appearance.selectCustomWallpaper(uri.toString())
            }
        }

    DisposableEffect(Unit) {
        telemetry.start(scope)
        peripheralMonitor.start()
        capabilityMonitor.start()
        onDispose {
            telemetry.stop()
            peripheralMonitor.stop()
            capabilityMonitor.stop()
        }
    }

    LaunchedEffect(commandFlow) {
        commandFlow.collect { command ->
            when (command) {
                DesktopCommand.TOGGLE_START -> desktop.toggleStartMenu()
                DesktopCommand.CYCLE_WINDOWS -> desktop.cycleWindows()
                DesktopCommand.CLOSE_ACTIVE -> desktop.closeActive()
                DesktopCommand.SHOW_DESKTOP -> desktop.minimizeAll()
                DesktopCommand.OPEN_FILES -> desktop.open(DesktopApp.FILES)
                DesktopCommand.OPEN_BROWSER -> desktop.open(DesktopApp.BROWSER)
                DesktopCommand.OPEN_TERMINAL -> desktop.open(DesktopApp.TERMINAL)
                DesktopCommand.OPEN_DESKTOP_CONTEXT ->
                    desktop.openContextMenu(null)
                DesktopCommand.DISMISS_OVERLAYS -> {
                    desktop.closeStartMenu()
                    desktop.closeContextMenu()
                }
                DesktopCommand.SNAP_LEFT -> desktop.snapActiveLeft()
                DesktopCommand.SNAP_RIGHT -> desktop.snapActiveRight()
            }
        }
    }

    MaterialTheme(
        colorScheme =
            if (useDarkTheme) {
                darkColorScheme()
            } else {
                lightColorScheme()
            },
    ) {
    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        DesktopWallpaper(
            preset = appearance.wallpaper,
            customUri = appearance.customWallpaperUri,
            modifier = Modifier
                .fillMaxSize()
                .desktopSecondaryClick { desktop.openContextMenu(null) }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            desktop.closeStartMenu()
                            desktop.closeContextMenu()
                        },
                        onLongPress = { desktop.openContextMenu(null) },
                    )
                },
        )

        DesktopIconsV2(
            desktop = desktop,
            modifier = Modifier.padding(start = 18.dp, top = 24.dp, bottom = 72.dp),
        )

        desktop.windows
            .filterNot { it.minimized }
            .sortedBy { it.zIndex }
            .forEach { window ->
                val alignment =
                    when (window.snap) {
                        WindowSnap.LEFT -> Alignment.CenterStart
                        WindowSnap.RIGHT -> Alignment.CenterEnd
                        WindowSnap.NONE -> Alignment.TopStart
                    }

                DesktopWindowView(
                    window = window,
                    desktop = desktop,
                    modifier = Modifier.align(alignment),
                ) {
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
                        DesktopApp.APPS -> InstalledAppsApp(desktopCapabilities)
                        DesktopApp.DOWNLOADS -> DownloadsApp()
                        DesktopApp.DISPLAYS -> DisplaysApp(desktopCapabilities)
                        DesktopApp.PERSONALIZATION -> PersonalizationApp(
                            selected = appearance.wallpaper,
                            customUri = appearance.customWallpaperUri,
                            themeMode = appearance.themeMode,
                            onSelect = appearance::selectWallpaper,
                            onThemeSelect = appearance::selectTheme,
                            onChooseCustom = {
                                wallpaperPicker.launch(
                                    arrayOf("image/*")
                                )
                            },
                            onClearCustom =
                                appearance::clearCustomWallpaper,
                        )
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
                            desktopCapabilities = desktopCapabilities,
                        )
                        DesktopApp.PERFORMANCE -> PerformanceApp(sample)
                    }
                }
            }

        PerformanceHud(
            sample = sample,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp),
        )

        if (desktop.contextMenuOpen) {
            DesktopContextMenu(
                desktop = desktop,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 68.dp),
            )
        }

        if (desktop.startMenuOpen) {
            StartMenuV2(
                desktop = desktop,
                peripherals = peripherals,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 66.dp),
            )
        }

        TaskbarV2(
            desktop = desktop,
            peripherals = peripherals,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    }
}

@Composable
private fun DesktopWindowView(
    window: DesktopWindow,
    desktop: DesktopController,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var x by remember(window.id) {
        mutableFloatStateOf(90f + (window.zIndex % 3) * 32f)
    }
    var y by remember(window.id) {
        mutableFloatStateOf(86f + (window.zIndex % 3) * 24f)
    }
    var widthFraction by remember(window.id) { mutableFloatStateOf(0.72f) }
    var heightFraction by remember(window.id) { mutableFloatStateOf(0.70f) }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx =
        with(density) { configuration.screenWidthDp.dp.toPx() }
            .coerceAtLeast(1f)
    val screenHeightPx =
        with(density) { configuration.screenHeightDp.dp.toPx() }
            .coerceAtLeast(1f)

    val windowModifier =
        when {
            window.maximized ->
                modifier
                    .fillMaxSize()
                    .padding(bottom = 58.dp)

            window.snap != WindowSnap.NONE ->
                modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.5f)
                    .padding(bottom = 58.dp)

            else ->
                modifier
                    .widthIn(min = 300.dp, max = 1200.dp)
                    .heightIn(min = 240.dp, max = 900.dp)
                    .fillMaxWidth(widthFraction)
                    .fillMaxHeight(heightFraction)
                    .offset {
                        IntOffset(
                            x.roundToInt(),
                            y.roundToInt(),
                        )
                    }
        }

    Surface(
        modifier = windowModifier.clickable(
            indication = null,
            interactionSource = remember {
                androidx.compose.foundation.interaction.MutableInteractionSource()
            },
        ) { desktop.focus(window.id) },
        shape = RoundedCornerShape(
            if (window.maximized || window.snap != WindowSnap.NONE) {
                0.dp
            } else {
                14.dp
            }
        ),
        shadowElevation = 14.dp,
        tonalElevation = 5.dp,
    ) {
        Box {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .pointerInput(
                            window.id,
                            window.maximized,
                            window.snap,
                        ) {
                            if (
                                !window.maximized &&
                                window.snap == WindowSnap.NONE
                            ) {
                                detectDragGestures(
                                    onDragStart = {
                                        desktop.focus(window.id)
                                    }
                                ) { change, drag ->
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
                    TextButton(
                        onClick = { desktop.minimize(window.id) }
                    ) {
                        Text("—")
                    }
                    TextButton(
                        onClick = {
                            if (window.snap != WindowSnap.NONE) {
                                desktop.restoreSnap(window.id)
                            } else {
                                desktop.toggleMaximize(window.id)
                            }
                        }
                    ) {
                        Text(
                            when {
                                window.snap != WindowSnap.NONE -> "↙"
                                window.maximized -> "▣"
                                else -> "□"
                            }
                        )
                    }
                    TextButton(
                        onClick = { desktop.close(window.id) }
                    ) {
                        Text("×")
                    }
                }

                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    content()
                }
            }

            if (
                !window.maximized &&
                window.snap == WindowSnap.NONE
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(30.dp)
                        .pointerHoverIcon(PointerIcon.Crosshair)
                        .pointerInput(window.id) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                widthFraction =
                                    (
                                        widthFraction +
                                            drag.x / screenWidthPx
                                    ).coerceIn(0.38f, 0.95f)
                                heightFraction =
                                    (
                                        heightFraction +
                                            drag.y / screenHeightPx
                                    ).coerceIn(0.42f, 0.92f)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("◢", fontSize = 14.sp)
                }
            }
        }
    }
}
