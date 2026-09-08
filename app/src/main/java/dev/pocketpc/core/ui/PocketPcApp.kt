package dev.pocketpc.core.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
import dev.pocketpc.core.runtime.GuestToolInstallManager
import dev.pocketpc.core.runtime.GuestToolPackageManager
import dev.pocketpc.core.runtime.PcApplicationTarget
import dev.pocketpc.core.runtime.NativeRuntimeHost
import dev.pocketpc.core.runtime.RootfsLinkManager
import dev.pocketpc.core.runtime.RuntimeInstallManager
import dev.pocketpc.core.runtime.RuntimePackageManager
import dev.pocketpc.core.runtime.RuntimeProbeEvidenceStore
import dev.pocketpc.core.runtime.WindowsRuntimeLayerPackageManager
import dev.pocketpc.core.storage.PocketDownloadImporter
import dev.pocketpc.core.storage.StorageRepository
import dev.pocketpc.core.system.collectSystemSnapshot
import dev.pocketpc.core.telemetry.TelemetryMonitor
import dev.pocketpc.core.terminal.LocalShellEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.io.File

@Composable
fun PocketPcApp(commandFlow: Flow<DesktopCommand>) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val pinStore = remember { DesktopPinStore(appContext) }
    val windowLayoutStore = remember { DesktopWindowLayoutStore(appContext) }
    val desktop = rememberSaveable(saver = DesktopController.saver(pinStore::load, pinStore::save)) {
        DesktopController(
            initialPinnedApps = pinStore.load(),
            onPinnedAppsChanged = pinStore::save,
        )
    }
    val browserSession = rememberSaveable(saver = BrowserSessionState.Saver) { BrowserSessionState() }
    val windowStateHolder = rememberSaveableStateHolder()
    val savedWindowIds = remember { mutableSetOf<String>() }
    val openWindowIds = desktop.windows.map { it.id }.toSet()
    LaunchedEffect(openWindowIds) {
        (savedWindowIds - openWindowIds).forEach(windowStateHolder::removeState)
        savedWindowIds.clear()
        savedWindowIds.addAll(openWindowIds)
    }
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
    val firstRunStore = remember {
        PocketPcFirstRunStore(appContext)
    }
    var showFirstRun by rememberSaveable {
        mutableStateOf(firstRunStore.shouldShow())
    }
    val terminal = remember { LocalShellEngine(appContext) }
    val runtimes = remember { RuntimePackageManager(appContext) }
    val installer = remember { RuntimeInstallManager(appContext, runtimes) }
    val guestToolInstaller = remember {
        GuestToolInstallManager(
            File(appContext.noBackupFilesDir, "runtime-tools")
        )
    }
    val guestToolPackages = remember {
        GuestToolPackageManager(appContext)
    }
    val runtimeProbeEvidence =
        remember {
            RuntimeProbeEvidenceStore(
                appContext,
            )
        }
    val windowsLayerPackages =
        remember {
            WindowsRuntimeLayerPackageManager(
                appContext,
            )
        }
    val linkManager = remember { RootfsLinkManager() }
    val nativeHost = remember { NativeRuntimeHost.status(appContext) }
    val substrate = remember { ExecutionSubstrateProbe.inspect(appContext) }
    val systemSnapshot = remember { collectSystemSnapshot(appContext) }
    val sample by telemetry.sample.collectAsStateWithLifecycle()
    val peripherals by peripheralMonitor.state.collectAsStateWithLifecycle()
    val desktopCapabilities by capabilityMonitor.state.collectAsStateWithLifecycle()
    var updateAttention by remember {
        mutableStateOf(
            PocketPcUpdateAttention.NONE
        )
    }

    PocketPcUpdateAutoCheck(
        onAttentionChanged = {
            updateAttention = it
        }
    )

    var storageRoot by rememberSaveable { mutableStateOf(storage.rootUriString) }
    var storagePickerError by rememberSaveable { mutableStateOf<String?>(null) }
    var runtimeManifestUri by rememberSaveable { mutableStateOf<String?>(null) }
    var runtimeRootfsUri by rememberSaveable { mutableStateOf<String?>(null) }
    var runtimeToolPackageUri by rememberSaveable { mutableStateOf<String?>(null) }
    var runtimeWindowsLayerUri by rememberSaveable { mutableStateOf<String?>(null) }
    var runtimeTargetUri by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var runtimeTargetName by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var runtimeTargetSize by rememberSaveable {
        mutableLongStateOf(0L)
    }
    val runtimeTarget =
        runtimeTargetUri?.let { uri ->
            runtimeTargetName?.let { name ->
                PcApplicationTarget(
                    uri = uri,
                    fileName = name,
                    sizeBytes = runtimeTargetSize,
                )
            }
        }

    fun selectRuntimeTarget(
        target: PcApplicationTarget,
    ) {
        runtimeTargetUri = target.uri
        runtimeTargetName = target.fileName
        runtimeTargetSize = target.sizeBytes
    }

    fun clearRuntimeTarget() {
        runtimeTargetUri = null
        runtimeTargetName = null
        runtimeTargetSize = 0L
    }

    var wallpaperEditorUri by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var wallpaperEditorTransform by rememberSaveable(stateSaver = WallpaperTransform.Saver) {
        mutableStateOf(
            appearance.customWallpaperTransform
        )
    }

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

    val guestToolPackagePicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                persistRead(uri)
                runtimeToolPackageUri = uri.toString()
            }
        }

    val windowsLayerPackagePicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                persistRead(uri)
                runtimeWindowsLayerUri =
                    uri.toString()
            }
        }

    val wallpaperPicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                persistRead(uri)
                wallpaperEditorUri = uri.toString()
                wallpaperEditorTransform =
                    WallpaperTransform()
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
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
    val desktopLayout = DesktopLayout(maxWidth.value, maxHeight.value)
    CompositionLocalProvider(LocalDesktopLayout provides desktopLayout) {
    Box(Modifier.fillMaxSize()) {
        BackHandler(enabled = desktop.startMenuOpen || desktop.contextMenuOpen || desktop.activeWindow != null) {
            if (desktop.startMenuOpen) desktop.closeStartMenu()
            else if (desktop.contextMenuOpen) desktop.closeContextMenu()
            else desktop.activeWindow?.let { desktop.minimize(it.id) }
        }
        DesktopWallpaper(
            preset = appearance.wallpaper,
            animationEnabled = desktop.windows.none {
                !it.minimized && (desktopLayout.compact || it.maximized)
            },
            customUri = appearance.customWallpaperUri,
            customTransform =
                appearance.customWallpaperTransform,
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
            modifier = Modifier.padding(start = 18.dp, top = 24.dp, bottom = (desktopLayout.taskbarHeightDp + 16f).dp),
        )

        desktop.windows
            .filterNot { it.minimized }
            .sortedBy { it.zIndex }
            .forEach { window ->
                key(window.id) {
                windowStateHolder.SaveableStateProvider(window.id) {
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
                        DesktopApp.BROWSER ->
                            BrowserApp(
                                session = browserSession,
                                storage = storage,
                                isActive = desktop.activeWindow?.id == window.id &&
                                    !desktop.startMenuOpen && !desktop.contextMenuOpen,
                                onOpenDownloads = { desktop.open(DesktopApp.DOWNLOADS) },
                                windowActions =
                                    BrowserWindowActions(
                                        minimized = {
                                            desktop.minimize(
                                                window.id
                                            )
                                        },
                                        toggleMaximize = {
                                            desktop.toggleMaximize(
                                                window.id
                                            )
                                        },
                                        close = {
                                            desktop.close(
                                                window.id
                                            )
                                        },
                                        maximized =
                                            window.maximized,
                                    ),
                            )
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
                            onOpenRuntime = { target ->
                                selectRuntimeTarget(target)
                                desktop.open(
                                    DesktopApp.RUNTIMES
                                )
                            },
                        )
                        DesktopApp.TERMINAL -> TerminalApp(terminal)
                        DesktopApp.APPS -> InstalledAppsApp(desktopCapabilities)
                        DesktopApp.DOWNLOADS ->
                            DownloadsApp(
                                repository = storage,
                                rootUri = storageRoot,
                                onOpenRuntime = { target ->
                                    selectRuntimeTarget(target)
                                    desktop.open(
                                        DesktopApp.RUNTIMES
                                    )
                                },
                            )
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
                            onEditCustom = {
                                appearance
                                    .customWallpaperUri
                                    ?.let { uri ->
                                        wallpaperEditorUri = uri
                                        wallpaperEditorTransform =
                                            appearance
                                                .customWallpaperTransform
                                    }
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
                            guestToolInstaller = guestToolInstaller,
                            guestToolPackages = guestToolPackages,
                            probeEvidenceStore = runtimeProbeEvidence,
                            windowsLayerPackages = windowsLayerPackages,
                            linkManager = linkManager,
                            nativeHost = nativeHost,
                            substrate = substrate,
                            target = runtimeTarget,
                            onClearTarget =
                                ::clearRuntimeTarget,
                            manifestUri = runtimeManifestUri,
                            rootfsUri = runtimeRootfsUri,
                            toolPackageUri = runtimeToolPackageUri,
                            windowsLayerUri = runtimeWindowsLayerUri,
                            onChooseManifest = {
                                manifestPicker.launch(
                                    arrayOf("application/json", "text/plain", "application/octet-stream")
                                )
                            },
                            onChooseRootfs = { rootfsPicker.launch(arrayOf("*/*")) },
                            onChooseToolPackage = {
                                guestToolPackagePicker.launch(
                                    arrayOf(
                                        "application/zip",
                                        "application/octet-stream",
                                    )
                                )
                            },
                            onClearToolPackage = {
                                runtimeToolPackageUri?.let { uriString ->
                                    runCatching {
                                        context.contentResolver.releasePersistableUriPermission(
                                            Uri.parse(uriString),
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                        )
                                    }
                                }
                                runtimeToolPackageUri = null
                            },
                            onChooseWindowsLayer = {
                                windowsLayerPackagePicker.launch(
                                    arrayOf(
                                        "application/zip",
                                        "application/octet-stream",
                                    )
                                )
                            },
                            onClearWindowsLayer = {
                                runtimeWindowsLayerUri?.let { uriString ->
                                    runCatching {
                                        context.contentResolver.releasePersistableUriPermission(
                                            Uri.parse(uriString),
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                        )
                                    }
                                }
                                runtimeWindowsLayerUri = null
                            },
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
                    .padding(start = 14.dp, bottom = (desktopLayout.taskbarHeightDp + 8f).dp),
            )
        }

        if (desktop.startMenuOpen) {
            StartMenuV2(
                desktop = desktop,
                peripherals = peripherals,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = (desktopLayout.taskbarHeightDp + 8f).dp),
            )
        }

        TaskbarV2(
            desktop = desktop,
            peripherals = peripherals,
            updateAttention = updateAttention,
            onUpdateClick = {
                desktop.open(DesktopApp.SYSTEM)
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        if (showFirstRun) {
            PocketPcFirstRunExperience(
                onComplete = {
                    firstRunStore.complete()
                    showFirstRun = false
                },
            )
        }

        wallpaperEditorUri?.let { uri ->
            WallpaperEditorDialog(
                uri = uri,
                initialTransform =
                    wallpaperEditorTransform,
                onDismiss = {
                    wallpaperEditorUri = null
                },
                onConfirm = { transform ->
                    appearance.selectCustomWallpaper(
                        uri = uri,
                        transform = transform,
                    )
                    wallpaperEditorTransform =
                        transform
                    wallpaperEditorUri = null
                },
            )
        }
    }
    }
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
    val layout = LocalDesktopLayout.current
    val density = LocalDensity.current
    val screenWidthPx =
        with(density) { layout.widthDp.dp.toPx() }
            .coerceAtLeast(1f)
    val screenHeightPx =
        with(density) { layout.heightDp.dp.toPx() }
            .coerceAtLeast(1f)
    val compactMobile = layout.compact
    val taskbarHeightDp = layout.taskbarHeightDp.dp
    val taskbarHeightPx =
        with(density) { taskbarHeightDp.toPx() }
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

    LaunchedEffect(screenWidthPx, workspaceHeightPx) {
        widthFraction = widthFraction.coerceIn(minWidthFraction, spec.maxWidthFraction)
        heightFraction = heightFraction.coerceIn(minHeightFraction, spec.maxHeightFraction)
        x = x.coerceIn(0f, screenWidthPx * (1f - widthFraction))
        y = y.coerceIn(0f, workspaceHeightPx * (1f - heightFraction))
    }

    val canHalfSnap =
        !compactMobile &&
            layout.widthDp / 2 >=
                spec.minWidthDp

    val integratedBrowserHeader =
        window.app == DesktopApp.BROWSER &&
            (
                compactMobile ||
                    window.maximized
            )

    val windowModifier =
        when {
            compactMobile ->
                modifier
                    .fillMaxSize()
                    .padding(bottom = taskbarHeightDp)

            window.maximized ->
                modifier
                    .fillMaxSize()
                    .padding(bottom = taskbarHeightDp)

            window.snap != WindowSnap.NONE -> {
                val snapFraction =
                    if (layout.widthDp / 2 >= spec.minWidthDp) {
                        0.5f
                    } else {
                        1.0f
                    }
                modifier
                    .fillMaxHeight()
                    .fillMaxWidth(snapFraction)
                    .padding(bottom = taskbarHeightDp)
            }

            else ->
                modifier
                    .widthIn(min = spec.minWidthDp.dp)
                    .heightIn(min = spec.minHeightDp.dp)
                    .fillMaxWidth(widthFraction)
                    .height(with(density) { (workspaceHeightPx * heightFraction).toDp() })
                    .offset {
                        IntOffset(
                            x.roundToInt(),
                            y.roundToInt(),
                        )
                    }
        }

    val focused = desktop.activeWindow?.id == window.id
    Surface(
        modifier = windowModifier.clickable(
            indication = null,
            interactionSource = remember {
                androidx.compose.foundation.interaction.MutableInteractionSource()
            },
        ) { desktop.focus(window.id) },
        shape = RoundedCornerShape(
            if (
                compactMobile ||
                window.maximized ||
                window.snap != WindowSnap.NONE
            ) {
                0.dp
            } else {
                14.dp
            }
        ),
        border = BorderStroke(1.dp, if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = if (focused) 16.dp else 4.dp,
        tonalElevation = 1.dp,
    ) {
        Box {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(
                            if (integratedBrowserHeader) {
                                0.dp
                            } else {
                                48.dp
                            }
                        )
                        .background(
                            if (focused) MaterialTheme.colorScheme.surfaceContainerHigh
                            else MaterialTheme.colorScheme.surfaceContainer
                        )
                        .pointerInput(
                            window.id,
                            window.maximized,
                            window.snap,
                            screenWidthPx,
                            workspaceHeightPx,
                        ) {
                            if (
                                !compactMobile &&
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
                    AppIconTile(app = window.app, size = 24)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        window.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                    if (!compactMobile) {
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
                    }
                    WindowControlButton(
                        label = "×",
                        danger = true,
                    ) {
                        desktop.close(window.id)
                    }
                }

                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .then(
                            if (spec.contentPaddingDp > 0) {
                                Modifier.padding(
                                    if (compactMobile) minOf(spec.contentPaddingDp, 8).dp
                                    else spec.contentPaddingDp.dp
                                )
                            } else {
                                Modifier
                            }
                        )
                ) {
                    CompositionLocalProvider(
                        LocalAppViewport provides AppViewport(maxWidth.value, maxHeight.value)
                    ) { content() }
                }
            }

            if (
                !compactMobile &&
                !window.maximized &&
                window.snap == WindowSnap.NONE
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(30.dp)
                        .pointerHoverIcon(PointerIcon.Crosshair)
                        .pointerInput(window.id, screenWidthPx, workspaceHeightPx) {
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
internal fun WindowControlButton(
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val description = when (label) {
        "×" -> "Fechar janela"
        "—" -> "Minimizar janela"
        "□" -> "Maximizar janela"
        "▣", "↙" -> "Restaurar janela"
        "◧" -> "Encaixar à esquerda"
        "◨" -> "Encaixar à direita"
        else -> label
    }
    TextButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics { contentDescription = description },
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (danger) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(label, fontSize = 16.sp)
    }
}
