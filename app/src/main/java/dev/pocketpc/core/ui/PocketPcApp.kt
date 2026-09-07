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
import dev.pocketpc.core.desktop.DesktopWindowLayoutStore
import dev.pocketpc.core.desktop.WindowGeometry
import dev.pocketpc.core.desktop.WindowSnap
import dev.pocketpc.core.desktop.windowSpec
import dev.pocketpc.core.runtime.ExecutionSubstrateProbe
import dev.pocketpc.core.runtime.NativeRuntimeHost
import dev.pocketpc.core.runtime.RootfsLinkManager
import dev.pocketpc.core.runtime.RuntimeInstallManager
import dev.pocketpc.core.runtime.RuntimePackageManager
import dev.pocketpc.core.storage.PocketDownloadImporter
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
    val windowLayoutStore = remember { DesktopWindowLayoutStore(appContext) }
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

    PocketPcUpdateAutoCheck()

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
                    scope.launch {
                        storage.ensurePocketDrive(
                            uri.toString()
                        )
                            .onFailure { failure ->
                                storagePickerError =
                                    failure.message
                                        ?: "Falha ao preparar o PocketDrive."
                            }
                    }
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

    LaunchedEffect(storageRoot) {
        if (storageRoot != null) {
            PocketDownloadImporter.importReady(
                context = appContext,
                storage = storage,
            )
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

    PocketPcTheme(
        darkTheme = useDarkTheme,
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
                    layoutStore = windowLayoutStore,
                    modifier = Modifier.align(alignment),
                ) {
                    when (window.app) {
                        DesktopApp.BROWSER -> BrowserApp(browserSession, storage)
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
                        DesktopApp.DOWNLOADS -> DownloadsApp(storage, storageRoot)
                        DesktopApp.STORE -> StoreApp()
                        DesktopApp.CONTROL_CENTER -> ControlCenterApp()
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
                            showPerformanceHud =
                                appearance.showPerformanceHud,
                            onPerformanceHudChange =
                                appearance::setPerformanceHud,
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

        if (appearance.showPerformanceHud) {
            PerformanceHud(
                sample = sample,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 10.dp),
            )
        }

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
    layoutStore: DesktopWindowLayoutStore,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx =
        with(density) { configuration.screenWidthDp.dp.toPx() }
            .coerceAtLeast(1f)
    val screenHeightPx =
        with(density) { configuration.screenHeightDp.dp.toPx() }
            .coerceAtLeast(1f)
    val taskbarHeightPx = with(density) { 58.dp.toPx() }
    val workspaceHeightPx =
        (screenHeightPx - taskbarHeightPx).coerceAtLeast(1f)
    val spec = window.app.windowSpec()
    val minWidthFraction =
        (
            with(density) { spec.minWidthDp.dp.toPx() } /
                screenWidthPx
        ).coerceIn(0.20f, spec.maxWidthFraction)
    val minHeightFraction =
        (
            with(density) { spec.minHeightDp.dp.toPx() } /
                workspaceHeightPx
        ).coerceIn(0.20f, spec.maxHeightFraction)

    val savedGeometry = remember(window.app) {
        layoutStore.load(window.app)
    }

    var widthFraction by remember(window.id) {
        mutableFloatStateOf(
            (savedGeometry?.widthFraction ?: spec.defaultWidthFraction)
                .coerceIn(minWidthFraction, spec.maxWidthFraction)
        )
    }
    var heightFraction by remember(window.id) {
        mutableFloatStateOf(
            (savedGeometry?.heightFraction ?: spec.defaultHeightFraction)
                .coerceIn(minHeightFraction, spec.maxHeightFraction)
        )
    }
    var x by remember(window.id) {
        val requested =
            spec.defaultXFraction * screenWidthPx
        val maxX =
            (
                screenWidthPx -
                    screenWidthPx * widthFraction
            ).coerceAtLeast(0f)
        mutableFloatStateOf(
            savedGeometry?.xFraction
                ?.times(screenWidthPx)
                ?: requested.coerceIn(0f, maxX)
        )
    }
    var y by remember(window.id) {
        val requested =
            spec.defaultYFraction * workspaceHeightPx
        val maxY =
            (
                workspaceHeightPx -
                    workspaceHeightPx * heightFraction
            ).coerceAtLeast(0f)
        mutableFloatStateOf(
            savedGeometry?.yFraction
                ?.times(workspaceHeightPx)
                ?: requested.coerceIn(0f, maxY)
        )
    }

    fun persistGeometry() {
        layoutStore.save(
            app = window.app,
            geometry = WindowGeometry(
                xFraction =
                    (x / screenWidthPx)
                        .coerceIn(0f, (1f - widthFraction).coerceAtLeast(0f)),
                yFraction =
                    (y / workspaceHeightPx)
                        .coerceIn(0f, (1f - heightFraction).coerceAtLeast(0f)),
                widthFraction = widthFraction,
                heightFraction = heightFraction,
            ),
        )
    }

    val canHalfSnap =
        configuration.screenWidthDp / 2 >=
            spec.minWidthDp

    val windowModifier =
        when {
            window.maximized ->
                modifier
                    .fillMaxSize()
                    .padding(bottom = 58.dp)

            window.snap != WindowSnap.NONE -> {
                val snapFraction =
                    if (configuration.screenWidthDp / 2 >= spec.minWidthDp) {
                        0.5f
                    } else {
                        1.0f
                    }
                modifier
                    .fillMaxHeight()
                    .fillMaxWidth(snapFraction)
                    .padding(bottom = 58.dp)
            }

            else ->
                modifier
                    .widthIn(min = spec.minWidthDp.dp)
                    .heightIn(min = spec.minHeightDp.dp)
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
                        .height(38.dp)
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
                                    },
                                    onDragEnd = { persistGeometry() },
                                    onDragCancel = { persistGeometry() },
                                ) { change, drag ->
                                    change.consume()
                                    val maxX =
                                        (
                                            screenWidthPx -
                                                screenWidthPx * widthFraction
                                        ).coerceAtLeast(0f)
                                    val maxY =
                                        (
                                            workspaceHeightPx -
                                                workspaceHeightPx * heightFraction
                                        ).coerceAtLeast(0f)
                                    x = (x + drag.x).coerceIn(0f, maxX)
                                    y = (y + drag.y).coerceIn(0f, maxY)
                                }
                            }
                        }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        window.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                    if (canHalfSnap) {
                        WindowControlButton("◧") {
                            desktop.snapLeft(window.id)
                        }
                        WindowControlButton("◨") {
                            desktop.snapRight(window.id)
                        }
                    }
                    WindowControlButton("—") {
                        desktop.minimize(window.id)
                    }
                    WindowControlButton(
                        when {
                            window.snap != WindowSnap.NONE -> "↙"
                            window.maximized -> "▣"
                            else -> "□"
                        }
                    ) {
                        if (window.snap != WindowSnap.NONE) {
                            desktop.restoreSnap(window.id)
                        } else {
                            desktop.toggleMaximize(window.id)
                        }
                    }
                    WindowControlButton(
                        label = "×",
                        danger = true,
                    ) {
                        desktop.close(window.id)
                    }
                }

                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .then(
                            if (spec.contentPaddingDp > 0) {
                                Modifier.padding(spec.contentPaddingDp.dp)
                            } else {
                                Modifier
                            }
                        )
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
                            detectDragGestures(
                                onDragEnd = { persistGeometry() },
                                onDragCancel = { persistGeometry() },
                            ) { change, drag ->
                                change.consume()
                                val xFraction =
                                    (x / screenWidthPx).coerceIn(0f, 1f)
                                val yFraction =
                                    (y / workspaceHeightPx).coerceIn(0f, 1f)
                                val maxWidth =
                                    minOf(
                                        spec.maxWidthFraction,
                                        (1f - xFraction).coerceAtLeast(
                                            minWidthFraction
                                        ),
                                    )
                                val maxHeight =
                                    minOf(
                                        spec.maxHeightFraction,
                                        (1f - yFraction).coerceAtLeast(
                                            minHeightFraction
                                        ),
                                    )
                                widthFraction =
                                    (
                                        widthFraction +
                                            drag.x / screenWidthPx
                                    ).coerceIn(
                                        minWidthFraction,
                                        maxWidth,
                                    )
                                heightFraction =
                                    (
                                        heightFraction +
                                            drag.y / workspaceHeightPx
                                    ).coerceIn(
                                        minHeightFraction,
                                        maxHeight,
                                    )
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

@Composable
private fun WindowControlButton(
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val background =
        if (danger) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    val foreground =
        if (danger) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Box(
        modifier = Modifier
            .size(width = 34.dp, height = 30.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .background(
                background.copy(alpha = 0.55f),
                RoundedCornerShape(8.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = foreground,
            fontSize = 11.sp,
        )
    }
}

