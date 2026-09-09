package dev.pocketpc.core.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import dev.pocketpc.core.runtime.GuestRuntimeProbe
import dev.pocketpc.core.runtime.GuestProbeRequirements
import dev.pocketpc.core.runtime.GuestToolInstallManager
import dev.pocketpc.core.runtime.GuestToolOverlayPlanner
import dev.pocketpc.core.runtime.GuestToolPackageManager
import dev.pocketpc.core.runtime.InstalledGuestTool
import dev.pocketpc.core.runtime.ExecutionSubstrateStatus
import dev.pocketpc.core.runtime.InstalledRuntime
import dev.pocketpc.core.runtime.NativeHostStatus
import dev.pocketpc.core.runtime.PcApplicationCompatibilityProbe
import dev.pocketpc.core.runtime.PcApplicationTarget
import dev.pocketpc.core.runtime.PcRuntimeExecutionGateState
import dev.pocketpc.core.runtime.PcRuntimeExecutionPlanner
import dev.pocketpc.core.runtime.PcRuntimeReadinessProbe
import dev.pocketpc.core.runtime.PcRuntimeStageState
import dev.pocketpc.core.runtime.ProotExecutionController
import dev.pocketpc.core.runtime.ProotInvocationPlan
import dev.pocketpc.core.runtime.ProotInvocationPlanner
import dev.pocketpc.core.runtime.RootfsLinkManager
import dev.pocketpc.core.runtime.RootfsExecutionReadinessProbe
import dev.pocketpc.core.runtime.RuntimeBindPlanner
import dev.pocketpc.core.runtime.RuntimeInstallManager
import dev.pocketpc.core.runtime.RuntimeIoCapabilityProbe
import dev.pocketpc.core.runtime.RuntimeManifestValidator
import dev.pocketpc.core.runtime.RuntimePackageManager
import dev.pocketpc.core.runtime.RuntimeProbeEvidenceStore
import dev.pocketpc.core.runtime.RuntimeProbeEvidenceState
import dev.pocketpc.core.runtime.RuntimeDisplayBridgeProbeController
import dev.pocketpc.core.runtime.RuntimeDiagnosticSuite
import dev.pocketpc.core.runtime.StagedRuntime
import dev.pocketpc.core.runtime.StagedGuestToolPackage
import dev.pocketpc.core.runtime.WindowsPrefixPlanner
import dev.pocketpc.core.runtime.WindowsPrefixReadinessProbe
import dev.pocketpc.core.runtime.WindowsRuntimeLayerPackageManager
import dev.pocketpc.core.runtime.StagedWindowsRuntimeLayer
import dev.pocketpc.core.runtime.WindowsRuntimeLayerDeployManager
import dev.pocketpc.core.runtime.DeployedWindowsRuntimeLayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class PendingRuntimeProbeExecution(
    val runtime: InstalledRuntime,
    val plan: ProotInvocationPlan,
    val probe: GuestRuntimeProbe,
    val layers: List<DeployedWindowsRuntimeLayer>,
)

private data class PendingRuntimeSuiteExecution(
    val runtime: InstalledRuntime,
    val layers: List<DeployedWindowsRuntimeLayer>,
)

private fun runtimeLayerStateKey(
    runtime: InstalledRuntime,
): String =
    runtime.manifest.id +
        "|" +
        runtime.manifest.version +
        "|" +
        runtime.manifest.rootfsSha256.lowercase()

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuntimeApp(
    manager: RuntimePackageManager,
    installer: RuntimeInstallManager,
    guestToolInstaller: GuestToolInstallManager,
    guestToolPackages: GuestToolPackageManager,
    probeEvidenceStore: RuntimeProbeEvidenceStore,
    windowsLayerPackages: WindowsRuntimeLayerPackageManager,
    linkManager: RootfsLinkManager,
    nativeHost: NativeHostStatus,
    substrate: ExecutionSubstrateStatus,
    target: PcApplicationTarget?,
    onClearTarget: () -> Unit,
    manifestUri: String?,
    rootfsUri: String?,
    toolPackageUri: String?,
    windowsLayerUri: String?,
    onChooseManifest: () -> Unit,
    onChooseRootfs: () -> Unit,
    onChooseToolPackage: () -> Unit,
    onClearToolPackage: () -> Unit,
    onChooseWindowsLayer: () -> Unit,
    onClearWindowsLayer: () -> Unit,
    onClearSelection: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val appContext =
        LocalContext.current.applicationContext
    val bindPlanner =
        remember(appContext) {
            RuntimeBindPlanner(appContext)
        }
    val ioHostCapabilities =
        remember(appContext) {
            RuntimeIoCapabilityProbe.inspect(appContext)
        }
    val executionController =
        remember {
            ProotExecutionController()
        }
    val displayBridgeProbeController =
        remember(executionController) {
            RuntimeDisplayBridgeProbeController(
                executionController,
            )
        }
    DisposableEffect(executionController) {
        onDispose { executionController.stopActive() }
    }
    var showSubstrateDetails by rememberSaveable { mutableStateOf(false) }
    var selectedProbeName by rememberSaveable { mutableStateOf(GuestRuntimeProbe.SHELL.name) }
    val selectedProbe = GuestRuntimeProbe.valueOf(selectedProbeName)
    var probeOutput by remember { mutableStateOf<String?>(null) }
    var showProbeOutput by rememberSaveable { mutableStateOf(false) }
    var pendingExecution by remember {
        mutableStateOf<
            PendingRuntimeProbeExecution?
        >(null)
    }
    var pendingSuiteExecution by remember {
        mutableStateOf<
            PendingRuntimeSuiteExecution?
        >(null)
    }
    var staged by remember { mutableStateOf<List<StagedRuntime>>(emptyList()) }
    var stagedTools by remember { mutableStateOf<List<StagedGuestToolPackage>>(emptyList()) }
    var stagedWindowsLayers by remember { mutableStateOf<List<StagedWindowsRuntimeLayer>>(emptyList()) }
    var installed by remember { mutableStateOf<List<InstalledRuntime>>(emptyList()) }
    var installedTools by remember { mutableStateOf<List<InstalledGuestTool>>(emptyList()) }
    var deployedWindowsLayersByRuntime by
        remember {
            mutableStateOf<
                Map<
                    String,
                    List<DeployedWindowsRuntimeLayer>
                >
            >(emptyMap())
        }
    var busy by remember { mutableStateOf(false) }
    var evidenceRevision by remember {
        mutableIntStateOf(0)
    }
    var status by remember { mutableStateOf<String?>(null) }
    var showPcRuntimeStages by rememberSaveable {
        mutableStateOf(false)
    }

    suspend fun reload() {
        staged = manager.discover()
        stagedTools = guestToolPackages.discover()
        stagedWindowsLayers =
            windowsLayerPackages.discover()
        val discoveredInstalled =
            installer.discover()
        installed = discoveredInstalled
        installedTools =
            guestToolInstaller.discover()

        val layerMap =
            linkedMapOf<
                String,
                List<DeployedWindowsRuntimeLayer>
            >()
        for (runtime in discoveredInstalled) {
            val layers =
                try {
                    val home =
                        bindPlanner
                            .homeDirectory(runtime)
                    val prefix =
                        WindowsPrefixPlanner.plan(
                            storageRoot = home,
                            profileId = "smoke",
                        )
                    WindowsRuntimeLayerDeployManager(
                        File(
                            home,
                            ".pocketpc/windows-layers",
                        ),
                    ).discover(prefix)
                } catch (
                    cancellation:
                        CancellationException
                ) {
                    throw cancellation
                } catch (_: Exception) {
                    emptyList()
                }
            layerMap[
                runtimeLayerStateKey(runtime)
            ] = layers
        }
        deployedWindowsLayersByRuntime =
            layerMap
    }

    LaunchedEffect(Unit) { reload() }

    val preparedRuntimes =
        installed.filter { runtime ->
            RootfsExecutionReadinessProbe
                .assess(runtime)
                .ready
        }

    val currentProbeEvidence =
        remember(
            installed,
            installedTools,
            deployedWindowsLayersByRuntime,
            evidenceRevision,
        ) {
            val states =
                preparedRuntimes.map { runtime ->
                    probeEvidenceStore.stateFor(
                        runtime = runtime,
                        tools = installedTools,
                        layers =
                            deployedWindowsLayersByRuntime[
                                runtimeLayerStateKey(
                                    runtime,
                                )
                            ].orEmpty(),
                    )
                }
            RuntimeProbeEvidenceState(
                box64SmokePassed =
                    states.any {
                        it.box64SmokePassed
                    },
                wineSmokePassed =
                    states.any {
                        it.wineSmokePassed
                    },
                displayBridgeSmokePassed =
                    states.any {
                        it.displayBridgeSmokePassed
                    },
                d3d11SmokePassed =
                    states.any {
                        it.d3d11SmokePassed
                    },
                graphicsPresentationSmokePassed =
                    states.any {
                        it.graphicsPresentationSmokePassed
                    },
                windowsProcessSmokePassed =
                    states.any {
                        it.windowsProcessSmokePassed
                    },
                winsockSmokePassed =
                    states.any {
                        it.winsockSmokePassed
                    },
                winmmAudioApiSmokePassed =
                    states.any {
                        it.winmmAudioApiSmokePassed
                    },
                rawInputApiSmokePassed =
                    states.any {
                        it.rawInputApiSmokePassed
                    },
            )
        }

    val windowsStateReady =
        remember(
            installed,
            installedTools,
            deployedWindowsLayersByRuntime,
            evidenceRevision,
        ) {
            preparedRuntimes.any { runtime ->
                val layers =
                    deployedWindowsLayersByRuntime[
                        runtimeLayerStateKey(
                            runtime,
                        )
                    ].orEmpty()
                val evidence =
                    probeEvidenceStore.stateFor(
                        runtime = runtime,
                        tools = installedTools,
                        layers = layers,
                    )
                evidence.wineSmokePassed &&
                    evidence.windowsProcessSmokePassed &&
                    runCatching {
                        val home =
                            bindPlanner
                                .homeDirectory(runtime)
                        val prefix =
                            WindowsPrefixPlanner
                                .plan(
                                    storageRoot = home,
                                    profileId = "smoke",
                                )
                        WindowsPrefixReadinessProbe
                            .assess(prefix)
                            .ready
                    }.getOrDefault(false)
            }
        }

    val pcReadiness =
        PcRuntimeReadinessProbe.assess(
            nativeHost = nativeHost,
            substrate = substrate,
            installedRuntimeCount =
                installed.size,
            preparedRuntimeCount =
                preparedRuntimes.size,
            ioHost = ioHostCapabilities,
            probeEvidence =
                currentProbeEvidence,
            windowsStateReady =
                windowsStateReady,
        )

    val toolOverlayPlan =
        GuestToolOverlayPlanner.plan(
            tools = installedTools,
            allowedHostRoots = bindPlanner.allowedHostRoots(),
        )

    suspend fun buildProbeInvocationPlan(
        runtime: InstalledRuntime,
        probe: GuestRuntimeProbe,
        layers: List<DeployedWindowsRuntimeLayer>,
    ): ProotInvocationPlan =
        withContext(Dispatchers.IO) {
            try {
                val requirementBlockers =
                    GuestProbeRequirements.blockers(
                        probe = probe,
                        installedToolIds =
                            installedTools
                                .map {
                                    it.manifest.id
                                }
                                .toSet(),
                        overlayValid =
                            toolOverlayPlan.valid,
                        installedWindowsLayerIds =
                            layers
                                .map {
                                    it.manifest.id
                                }
                                .toSet(),
                    )

                if (
                    requirementBlockers
                        .isNotEmpty()
                ) {
                    ProotInvocationPlan(
                        ready = false,
                        argv = emptyList(),
                        environment =
                            emptyMap(),
                        blockers =
                            (
                                requirementBlockers +
                                    toolOverlayPlan
                                        .blockers
                                ).distinct(),
                    )
                } else {
                    ProotInvocationPlanner
                        .buildProbe(
                            runtime = runtime,
                            substrate =
                                substrate,
                            probe = probe,
                            binds =
                                bindPlanner
                                    .base(runtime) +
                                    toolOverlayPlan
                                        .binds,
                            allowedHostRoots =
                                bindPlanner
                                    .allowedHostRoots(),
                        )
                }
            } catch (
                cancellation:
                    CancellationException
            ) {
                throw cancellation
            } catch (failure: Exception) {
                ProotInvocationPlan(
                    ready = false,
                    argv = emptyList(),
                    environment =
                        emptyMap(),
                    blockers =
                        listOf(
                            "BIND_PLAN_FAILED:" +
                                (
                                    failure.message
                                        ?: failure
                                            .javaClass
                                            .simpleName
                                    ),
                        ),
                )
            }
        }

    val scrollState = rememberLazyListState()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = scrollState,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "heading") {
            Text("Runtimes", style = MaterialTheme.typography.titleMedium)
            Text("Role para ver todas as etapas e opções.", style = MaterialTheme.typography.bodySmall)
        }

        item(key = "diagnostic") {
        Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    when {
                        !substrate.packagedHostReady -> "O componente nativo desta instalação está indisponível."
                        !substrate.artifactContractApproved -> "O PRoot ainda precisa ser incluído e aprovado nesta versão."
                        !substrate.policyDigestsVerified -> "A política do runtime precisa ser verificada."
                        !substrate.artifactIntegrityVerified -> "Os arquivos do runtime não passaram na verificação de integridade."
                        substrate.prootReady -> "Componentes verificados. A execução ainda exige rootfs preparado e confirmação."
                        else -> "O runtime Linux ainda possui requisitos pendentes."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { showSubstrateDetails = !showSubstrateDetails }) {
                    Text(if (showSubstrateDetails) "Ocultar diagnóstico" else "Ver requisitos do runtime")
                }
                if (showSubstrateDetails) {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Execution substrate", style = MaterialTheme.typography.titleSmall)
                        RuntimeDetailRow("Native host", if (nativeHost.loaded) "LOADED" else "FAILED")
                        RuntimeDetailRow("Substrate", substrate.state)
                        RuntimeDetailRow(
                            "PRoot components",
                            when {
                                substrate.prootReady ->
                                    "APPROVED / VERIFIED"
                                substrate.components.any {
                                    it.exists
                                } ->
                                    "CANDIDATE / NOT APPROVED"
                                else ->
                                    "NOT BUNDLED"
                            },
                        )
                        Text(nativeHost.probe, style = MaterialTheme.typography.bodySmall)
                        Text(nativeHost.graphicsProbe, style = MaterialTheme.typography.bodySmall)
                        val hostNetworkState =
                            when {
                                ioHostCapabilities.networkValidated -> "VALIDATED"
                                ioHostCapabilities.networkInternetCapable -> "INTERNET"
                                else -> "OFFLINE"
                            }
                        RuntimeDetailRow(
                            "Guest tools",
                            when {
                                installedTools.isEmpty() -> "NONE INSTALLED"
                                toolOverlayPlan.valid ->
                                    installedTools.joinToString { tool ->
                                        tool.manifest.id + " " + tool.manifest.version
                                    }
                                else ->
                                    "BLOCKED / ATTESTATION FAILED"
                            },
                        )
                        RuntimeDetailRow(
                            "Host IO",
                            "audio=${ioHostCapabilities.audioOutputCount}, " +
                                "keyboard=${ioHostCapabilities.keyboardCount}, " +
                                "mouse=${ioHostCapabilities.mouseCount}, " +
                                "gamepad=${ioHostCapabilities.gamepadCount}, " +
                                "network=$hostNetworkState",
                        )

                        if (!substrate.prootReady) {
                            Text(
                                "O substrate continua BLOCKED: componentes podem estar " +
                                    "ausentes ou ainda não aprovados/verificados. " +
                                    "Nenhum candidato é tratado como runtime executável.",
                                style =
                                    MaterialTheme.typography.bodySmall,
                            )
                        }
                        substrate.components.forEach { component ->
                            RuntimeDetailRow(component.fileName, when {
                                !component.exists -> "AUSENTE"
                                !component.readable -> "SEM LEITURA"
                                component.executableRequired && !component.executable -> "SEM EXECUÇÃO"
                                else -> "PRESENTE"
                            })
                        }
                        substrate.approvalErrors.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }

            }
        }

        }

        target?.let { selectedTarget ->
            val compatibility =
                PcApplicationCompatibilityProbe.assess(
                    fileName =
                        selectedTarget.fileName,
                    readiness = pcReadiness,
                )
            val executionPlan =
                PcRuntimeExecutionPlanner.build(
                    target = selectedTarget,
                    readiness = pcReadiness,
                    compatibility = compatibility,
                )

            item(key = "selected-target") {
            Surface(
                tonalElevation = 3.dp,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement =
                        Arrangement.spacedBy(6.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement =
                                Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                "Alvo do Explorador",
                                style =
                                    MaterialTheme.typography
                                        .titleSmall,
                            )
                            Text(
                                selectedTarget.fileName,
                                style =
                                    MaterialTheme.typography
                                        .bodyMedium,
                            )
                            if (
                                selectedTarget.sizeBytes > 0L
                            ) {
                                Text(
                                    formatBytes(
                                        selectedTarget
                                            .sizeBytes
                                    ),
                                    style =
                                        MaterialTheme.typography
                                            .labelSmall,
                                )
                            }
                        }
                        TextButton(
                            onClick = onClearTarget,
                        ) {
                            Text("Limpar alvo")
                        }
                    }

                    Text(
                        compatibility.displayName +
                            " • " +
                            compatibility.state.name,
                        style =
                            MaterialTheme.typography
                                .labelMedium,
                        color =
                            if (
                                executionPlan
                                    .launchEligible
                            ) {
                                MaterialTheme
                                    .colorScheme.primary
                            } else {
                                MaterialTheme
                                    .colorScheme.error
                            },
                    )
                    Text(
                        compatibility.detail,
                        style =
                            MaterialTheme.typography
                                .bodySmall,
                    )
                    Text(
                        executionPlan.nextAction,
                        style =
                            MaterialTheme.typography
                                .bodySmall,
                        color =
                            MaterialTheme.colorScheme
                                .onSurfaceVariant,
                    )

                    executionPlan.gates
                        .filter {
                            it.state !=
                                PcRuntimeExecutionGateState
                                    .READY
                        }
                        .take(4)
                        .forEach { gate ->
                            Text(
                                "• " +
                                    gate.label +
                                    ": " +
                                    gate.state.name,
                                style =
                                    MaterialTheme.typography
                                        .labelSmall,
                            )
                        }

                    Surface(
                        tonalElevation = 1.dp,
                        shape =
                            MaterialTheme.shapes.small,
                    ) {
                        Text(
                            if (
                                executionPlan.launchEligible
                            ) {
                                "Runtime base elegível; " +
                                    "o executor Windows " +
                                    "específico ainda precisa " +
                                    "ser conectado antes de " +
                                    "oferecer execução."
                            } else {
                                "Execução bloqueada pelos gates"
                            },
                            modifier =
                                Modifier.padding(8.dp),
                            style =
                                MaterialTheme.typography
                                    .labelSmall,
                        )
                    }
                }
            }
        }

        }

        item(key = "compatibility") {
        Surface(
            tonalElevation = 2.dp,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement =
                    Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Compatibilidade de PC",
                    style =
                        MaterialTheme.typography
                            .titleSmall,
                )
                Text(
                    pcReadiness.target,
                    style =
                        MaterialTheme.typography
                            .bodySmall,
                )
                Text(
                    pcReadiness.readyCount
                        .toString() +
                        "/" +
                        pcReadiness.stages.size +
                        " estágios READY",
                    style =
                        MaterialTheme.typography
                            .labelSmall,
                )

                TextButton(
                    onClick = {
                        showPcRuntimeStages =
                            !showPcRuntimeStages
                    },
                ) {
                    Text(
                        if (showPcRuntimeStages) {
                            "Ocultar etapas"
                        } else {
                            "Ver etapas"
                        }
                    )
                }

                if (showPcRuntimeStages) {
                    pcReadiness.stages.forEach {
                        stage ->
                    val stateText =
                        when (stage.state) {
                            PcRuntimeStageState.READY ->
                                "READY"
                            PcRuntimeStageState.BLOCKED ->
                                "BLOCKED"
                            PcRuntimeStageState.NOT_IMPLEMENTED ->
                                "NOT_IMPLEMENTED"
                            PcRuntimeStageState.UNKNOWN ->
                                "UNKNOWN"
                        }

                    Surface(
                        tonalElevation = 1.dp,
                        shape =
                            MaterialTheme.shapes.small,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(2.dp),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    stage.label,
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodyMedium,
                                )
                                Text(
                                    stateText,
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall,
                                    color =
                                        when (
                                            stage.state
                                        ) {
                                            PcRuntimeStageState.READY ->
                                                MaterialTheme
                                                    .colorScheme
                                                    .primary
                                            PcRuntimeStageState.BLOCKED ->
                                                MaterialTheme
                                                    .colorScheme
                                                    .error
                                            else ->
                                                MaterialTheme
                                                    .colorScheme
                                                    .onSurfaceVariant
                                        },
                                )
                            }
                            Text(
                                stage.detail,
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,
                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .onSurfaceVariant,
                            )
                        }
                    }
                }
                }

                Text(
                    if (pcReadiness.executableReady) {
                        "EXECUTION_READY"
                    } else {
                        "Execução de .exe continua bloqueada. " +
                            "O PocketPC não marcará Roblox/Windows " +
                            "como pronto antes de todos os gates " +
                            "necessários passarem."
                    },
                    style =
                        MaterialTheme.typography
                            .bodySmall,
                )
            }
        }

        }

        item(key = "import-controls") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Importar rootfs", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = onChooseManifest, enabled = !busy) {
                Text(if (manifestUri == null) "Manifesto" else "Manifesto ✓")
            }
            OutlinedButton(onClick = onChooseRootfs, enabled = !busy) {
                Text(if (rootfsUri == null) "Rootfs" else "Rootfs ✓")
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
                enabled = !busy && manifestUri != null && rootfsUri != null,
                onClick = {
                    val manifest = manifestUri ?: return@Button
                    val rootfs = rootfsUri ?: return@Button
                    busy = true
                    status = "Verificando espaço, tamanho e SHA-256…"
                    scope.launch {
                        manager.stage(manifest, rootfs)
                            .onSuccess {
                                status = "STAGED_VERIFIED: ${it.manifest.name} ${it.manifest.version}"
                                onClearSelection()
                                reload()
                            }
                            .onFailure {
                                status = "STAGING FAILED: ${it.message ?: it.javaClass.simpleName}"
                            }
                        busy = false
                    }
                },
            ) {
                Text("Verificar e preparar")
            }
            TextButton(
                onClick = onClearSelection,
                enabled = !busy && (manifestUri != null || rootfsUri != null),
            ) {
                Text("Limpar")
            }
        }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.contains("FAILED")) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
        }

        HorizontalDivider()

        }
        }

            item(key = "guest-tool-import") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Ferramentas do runtime PC",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "Importe um pacote ZIP confiável. O PocketPC valida manifesto, commit, hashes e arquivos antes de instalar.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        OutlinedButton(
                            onClick = onChooseToolPackage,
                            enabled = !busy,
                        ) {
                            Text(
                                if (toolPackageUri == null) {
                                    "Selecionar pacote"
                                } else {
                                    "Pacote ✓"
                                }
                            )
                        }
                        Button(
                            enabled = !busy && toolPackageUri != null,
                            onClick = {
                                val uri =
                                    toolPackageUri
                                        ?: return@Button
                                busy = true
                                status =
                                    "Verificando pacote guest tool…"
                                scope.launch {
                                    guestToolPackages
                                        .stageZip(uri)
                                        .onSuccess {
                                            status =
                                                "TOOL_STAGED_VERIFIED: " +
                                                    it.manifest.id +
                                                    " " +
                                                    it.manifest.version
                                            onClearToolPackage()
                                            reload()
                                        }
                                        .onFailure {
                                            status =
                                                "TOOL STAGING FAILED: " +
                                                    (
                                                        it.message
                                                            ?: it.javaClass
                                                                .simpleName
                                                    )
                                        }
                                    busy = false
                                }
                            },
                        ) {
                            Text("Verificar pacote")
                        }
                        TextButton(
                            onClick = onClearToolPackage,
                            enabled = !busy && toolPackageUri != null,
                        ) {
                            Text("Limpar")
                        }
                    }
                }
            }

            if (stagedTools.isNotEmpty()) {
                item(key = "guest-tools-staged-title") {
                    Text(
                        "TOOLS_STAGED_VERIFIED",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }

            items(
                items = stagedTools,
                key = {
                    "tool-staged:" +
                        it.manifest.id +
                        ":" +
                        it.manifest.version
                },
            ) { tool ->
                Surface(
                    tonalElevation = 2.dp,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            tool.manifest.id +
                                " " +
                                tool.manifest.version
                        )
                        Text(
                            "commit " +
                                tool.manifest.sourceCommit.take(12) +
                                "… • " +
                                tool.manifest.architecture,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        FlowRow(
                            horizontalArrangement =
                                Arrangement.spacedBy(6.dp),
                        ) {
                            Button(
                                enabled = !busy,
                                onClick = {
                                    busy = true
                                    status =
                                        "Instalando guest tool verificado…"
                                    scope.launch {
                                        guestToolInstaller
                                            .install(tool.directory)
                                            .onSuccess {
                                                status =
                                                    "TOOL_INSTALLED_ATTESTED: " +
                                                        it.manifest.id +
                                                        " " +
                                                        it.manifest.version
                                                reload()
                                            }
                                            .onFailure {
                                                status =
                                                    "TOOL INSTALL FAILED: " +
                                                        (
                                                            it.message
                                                                ?: it.javaClass
                                                                    .simpleName
                                                        )
                                            }
                                        busy = false
                                    }
                                },
                            ) {
                                Text("Instalar")
                            }
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        val removed =
                                            guestToolPackages
                                                .remove(tool)
                                        status =
                                            if (removed) {
                                                "Staging da ferramenta removido."
                                            } else {
                                                "Não foi possível remover staging da ferramenta."
                                            }
                                        reload()
                                        busy = false
                                    }
                                },
                            ) {
                                Text("Remover staging")
                            }
                        }
                    }
                }
            }

            if (installedTools.isNotEmpty()) {
                item(key = "guest-tools-installed-title") {
                    Text(
                        "GUEST_TOOLS_INSTALLED",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }

            items(
                items = installedTools,
                key = {
                    "tool-installed:" +
                        it.manifest.id +
                        ":" +
                        it.manifest.version
                },
            ) { tool ->
                Surface(
                    tonalElevation = 2.dp,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            tool.manifest.id +
                                " " +
                                tool.manifest.version
                        )
                        Text(
                            tool.manifest.guestRoot +
                                "/" +
                                tool.manifest.entrypoint,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    val removed =
                                        guestToolInstaller
                                            .remove(tool)
                                    status =
                                        if (removed) {
                                            "Guest tool removido."
                                        } else {
                                            "Não foi possível remover guest tool."
                                        }
                                    reload()
                                    busy = false
                                }
                            },
                        ) {
                            Text("Desinstalar")
                        }
                    }
                }
            }

            item(key = "windows-layer-import") {
                Column(
                    verticalArrangement =
                        Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Camadas Direct3D → Vulkan",
                        style =
                            MaterialTheme.typography
                                .titleSmall,
                    )
                    Text(
                        "DXVK/vkd3d são verificados separadamente do Wine. Aplicação no prefixo só é liberada após o smoke Win64.",
                        style =
                            MaterialTheme.typography
                                .bodySmall,
                    )
                    FlowRow(
                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(4.dp),
                    ) {
                        OutlinedButton(
                            onClick =
                                onChooseWindowsLayer,
                            enabled = !busy,
                        ) {
                            Text(
                                if (
                                    windowsLayerUri ==
                                    null
                                ) {
                                    "Selecionar camada"
                                } else {
                                    "Camada ✓"
                                }
                            )
                        }
                        Button(
                            enabled =
                                !busy &&
                                    windowsLayerUri !=
                                    null,
                            onClick = {
                                val uri =
                                    windowsLayerUri
                                        ?: return@Button
                                busy = true
                                status =
                                    "Verificando camada gráfica…"
                                scope.launch {
                                    windowsLayerPackages
                                        .stageZip(uri)
                                        .onSuccess {
                                            status =
                                                "WINDOWS_LAYER_STAGED_VERIFIED: " +
                                                    it.manifest.id +
                                                    " " +
                                                    it.manifest.version
                                            onClearWindowsLayer()
                                            reload()
                                        }
                                        .onFailure {
                                            status =
                                                "WINDOWS LAYER STAGING FAILED: " +
                                                    (
                                                        it.message
                                                            ?: it.javaClass
                                                                .simpleName
                                                    )
                                        }
                                    busy = false
                                }
                            },
                        ) {
                            Text("Verificar camada")
                        }
                        TextButton(
                            onClick =
                                onClearWindowsLayer,
                            enabled =
                                !busy &&
                                    windowsLayerUri !=
                                    null,
                        ) {
                            Text("Limpar")
                        }
                    }
                }
            }

            if (stagedWindowsLayers.isNotEmpty()) {
                item(key = "windows-layers-staged-title") {
                    Text(
                        "WINDOWS_LAYERS_STAGED_VERIFIED",
                        style =
                            MaterialTheme.typography
                                .titleSmall,
                    )
                }
            }

            items(
                items = stagedWindowsLayers,
                key = {
                    "windows-layer:" +
                        it.manifest.id +
                        ":" +
                        it.manifest.version
                },
            ) { layer ->
                Surface(
                    tonalElevation = 2.dp,
                    shape =
                        MaterialTheme.shapes
                            .medium,
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            layer.manifest.id +
                                " " +
                                layer.manifest.version
                        )
                        Text(
                            layer.manifest.files
                                .joinToString {
                                    it.destinationName
                                },
                            style =
                                MaterialTheme.typography
                                    .bodySmall,
                        )
                        Text(
                            "Aguardando prefixo Wine validado para implantação.",
                            style =
                                MaterialTheme.typography
                                    .labelSmall,
                        )
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    val removed =
                                        windowsLayerPackages
                                            .remove(layer)
                                    status =
                                        if (removed) {
                                            "Staging gráfico removido."
                                        } else {
                                            "Não foi possível remover staging gráfico."
                                        }
                                    reload()
                                    busy = false
                                }
                            },
                        ) {
                            Text("Remover staging")
                        }
                    }
                }
            }

            item {
                Text("STAGED_VERIFIED", style = MaterialTheme.typography.titleSmall)
            }

            if (staged.isEmpty()) {
                item { Text("Nenhum rootfs em staging.", style = MaterialTheme.typography.bodySmall) }
            }

            items(
                items = staged,
                key = { "staged:${it.manifest.id}:${it.manifest.version}" },
            ) { runtime ->
                StagedRuntimeCard(
                    runtime = runtime,
                    enabled = !busy,
                    onAudit = {
                        busy = true
                        status = "Recalculando SHA-256 de ${runtime.manifest.name}…"
                        scope.launch {
                            val audit = manager.audit(runtime)
                            status = if (audit.valid) {
                                "AUDIT_OK: ${runtime.manifest.name} ${runtime.manifest.version}"
                            } else audit.message
                            busy = false
                        }
                    },
                    onInstall = if (RuntimeManifestValidator.canExtract(runtime.manifest)) {
                        {
                            busy = true
                            status = "Extraindo rootfs como dados seguros…"
                            scope.launch {
                                installer.install(runtime)
                                    .onSuccess {
                                        status = "INSTALLED_DATA: ${it.manifest.name} ${it.manifest.version}"
                                        reload()
                                    }
                                    .onFailure {
                                        status = "INSTALL FAILED: ${it.message ?: it.javaClass.simpleName}"
                                    }
                                busy = false
                            }
                        }
                    } else null,
                    onRemove = {
                        busy = true
                        scope.launch {
                            val removed = manager.remove(runtime)
                            status = if (removed) "Staging removido."
                            else "Não foi possível remover staging."
                            reload()
                            busy = false
                        }
                    },
                )
            }

            item(key = "guest-probes") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Testar ambiente Linux", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GuestRuntimeProbe.entries.forEach { probe ->
                            FilterChip(selected = selectedProbe == probe, enabled = !busy,
                                onClick = { selectedProbeName = probe.name },
                                label = { Text(probe.label) })
                        }
                    }
                    Text(selectedProbe.description, style = MaterialTheme.typography.bodySmall)
                    val requiredTools =
                        GuestProbeRequirements
                            .requiredToolIds(selectedProbe)
                    if (requiredTools.isNotEmpty()) {
                        Text(
                            "Requer: " +
                                requiredTools
                                    .sorted()
                                    .joinToString(),
                            style =
                                MaterialTheme.typography
                                    .labelSmall,
                        )
                    }
                    Text("Os testes ficam disponíveis quando o PRoot, o rootfs e as ferramentas exigidas estiverem prontos.",
                        style = MaterialTheme.typography.bodySmall)
                    probeOutput?.let { output ->
                        TextButton(onClick = { showProbeOutput = !showProbeOutput }) {
                            Text(if (showProbeOutput) "Ocultar resultado" else "Ver resultado do teste")
                        }
                        if (showProbeOutput) {
                            SelectionContainer { Text(output, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("INSTALLED_DATA / LINKS_PREPARED", style = MaterialTheme.typography.titleSmall)
            }

            if (installed.isEmpty()) {
                item {
                    Text(
                        "Nenhum rootfs extraído. Schema v2 é necessário.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            items(
                items = installed,
                key = { "installed:${it.manifest.id}:${it.manifest.version}" },
            ) { runtime ->
                val deployedWindowsLayers =
                    deployedWindowsLayersByRuntime[
                        runtimeLayerStateKey(runtime)
                    ].orEmpty()

                val invocationPlan by produceState(
                    initialValue =
                        ProotInvocationPlan(
                            false,
                            emptyList(),
                            emptyMap(),
                            listOf(
                                "BIND_PLAN_PREPARING",
                            ),
                        ),
                    key1 = runtime,
                    key2 = substrate,
                    key3 =
                        selectedProbe to
                            installedTools.map {
                                it.manifest.id +
                                    ":" +
                                    it.manifest
                                        .version
                            } to
                            deployedWindowsLayers
                                .map {
                                    it.manifest.id +
                                        ":" +
                                        it.manifest
                                            .version
                                },
                ) {
                    value =
                        buildProbeInvocationPlan(
                            runtime = runtime,
                            probe =
                                selectedProbe,
                            layers =
                                deployedWindowsLayers,
                        )
                }
                val executionRequestReady =
                    invocationPlan.argv.isNotEmpty() &&
                        invocationPlan.blockers ==
                        listOf(
                            ProotExecutionController
                                .EXECUTION_APPROVAL_BLOCKER
                        )
                val runtimeProbeEvidence =
                    remember(
                        runtime,
                        installedTools,
                        deployedWindowsLayers,
                        evidenceRevision,
                    ) {
                        probeEvidenceStore.stateFor(
                            runtime = runtime,
                            tools = installedTools,
                            layers =
                                deployedWindowsLayers,
                        )
                    }
                val runtimeHome =
                    remember(runtime) {
                        runCatching {
                            bindPlanner
                                .homeDirectory(runtime)
                        }.getOrNull()
                    }
                val runtimePrefixPlan =
                    remember(
                        runtimeHome,
                        evidenceRevision,
                    ) {
                        runtimeHome?.let { home ->
                            WindowsPrefixPlanner.plan(
                                storageRoot = home,
                                profileId = "smoke",
                            )
                        }
                    }
                val runtimePrefixReady =
                    runtimePrefixPlan?.let { plan ->
                        WindowsPrefixReadinessProbe
                            .assess(plan)
                            .ready
                    } == true
                val windowsLayerDeployManager =
                    remember(runtimeHome) {
                        runtimeHome?.let { home ->
                            WindowsRuntimeLayerDeployManager(
                                File(
                                    home,
                                    ".pocketpc/windows-layers",
                                ),
                            )
                        }
                    }

                InstalledRuntimeCard(
                    runtime = runtime,
                    enabled = !busy,
                    executionReady =
                        executionRequestReady,
                    onRunSuite =
                        if (
                            RootfsExecutionReadinessProbe
                                .assess(runtime)
                                .ready &&
                            !busy
                        ) {
                            {
                                pendingSuiteExecution =
                                    PendingRuntimeSuiteExecution(
                                        runtime =
                                            runtime,
                                        layers =
                                            deployedWindowsLayers,
                                    )
                            }
                        } else {
                            null
                        },
                    onRunProbe =
                        if (
                            executionRequestReady &&
                            !busy
                        ) {
                            {
                                pendingExecution =
                                    PendingRuntimeProbeExecution(
                                        runtime =
                                            runtime,
                                        plan =
                                            invocationPlan,
                                        probe =
                                            selectedProbe,
                                        layers =
                                            deployedWindowsLayers,
                                    )
                            }
                        } else {
                            null
                        },
                    onPrepareLinks = if (runtime.stats.linksRecorded > 0 && !runtime.linksPrepared) {
                        {
                            busy = true
                            status = "Validando e preparando links Linux…"
                            scope.launch {
                                val result = linkManager.prepare(runtime)
                                status = result.message
                                reload()
                                busy = false
                            }
                        }
                    } else null,
                    onVerifyLinks = if (runtime.linksPrepared) {
                        {
                            busy = true
                            scope.launch {
                                val result = linkManager.verify(runtime)
                                status = result.message
                                reload()
                                busy = false
                            }
                        }
                    } else null,
                    onRemove = {
                        busy = true
                        scope.launch {
                            val removed = installer.remove(runtime)
                            status = if (removed) "Instalação removida com NOFOLLOW."
                            else "Não foi possível remover instalação."
                            reload()
                            busy = false
                        }
                    },
                )
                if (
                    runtimeProbeEvidence
                        .wineSmokePassed &&
                    runtimePrefixReady &&
                    runtimePrefixPlan != null &&
                    windowsLayerDeployManager !=
                        null
                ) {
                    Surface(
                        tonalElevation = 1.dp,
                        shape =
                            MaterialTheme.shapes
                                .medium,
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "Camadas gráficas deste prefixo",
                                style =
                                    MaterialTheme.typography
                                        .titleSmall,
                            )
                            if (
                                stagedWindowsLayers
                                    .isEmpty()
                            ) {
                                Text(
                                    "Nenhuma camada gráfica em staging.",
                                    style =
                                        MaterialTheme.typography
                                            .bodySmall,
                                )
                            }
                            stagedWindowsLayers
                                .forEach { layer ->
                                    val alreadyDeployed =
                                        deployedWindowsLayers
                                            .any { deployed ->
                                                deployed.manifest ==
                                                    layer.manifest
                                            }
                                    Button(
                                        enabled =
                                            !busy &&
                                                !alreadyDeployed,
                                        onClick = {
                                            busy = true
                                            status =
                                                "Implantando " +
                                                    layer.manifest.id +
                                                    " com backup/rollback…"
                                            scope.launch {
                                                windowsLayerDeployManager
                                                    .deploy(
                                                        layer = layer,
                                                        prefixPlan =
                                                            runtimePrefixPlan,
                                                    )
                                                    .onSuccess {
                                                        status =
                                                            "WINDOWS_LAYER_DEPLOYED_ATTESTED: " +
                                                                it.manifest.id +
                                                                " " +
                                                                it.manifest.version
                                                        reload()
                                                        evidenceRevision +=
                                                            1
                                                    }
                                                    .onFailure {
                                                        status =
                                                            "WINDOWS LAYER DEPLOY FAILED: " +
                                                                (
                                                                    it.message
                                                                        ?: it.javaClass
                                                                            .simpleName
                                                                )
                                                    }
                                                busy = false
                                            }
                                        },
                                    ) {
                                        Text(
                                            if (alreadyDeployed) {
                                                layer.manifest.id +
                                                    " aplicado ✓"
                                            } else {
                                                "Aplicar " +
                                                    layer.manifest.id
                                            },
                                        )
                                    }
                                }

                            deployedWindowsLayers
                                .forEach { deployed ->
                                    TextButton(
                                        enabled = !busy,
                                        onClick = {
                                            busy = true
                                            scope.launch {
                                                windowsLayerDeployManager
                                                    .remove(
                                                        deployed = deployed,
                                                        prefixPlan =
                                                            runtimePrefixPlan,
                                                    )
                                                    .onSuccess {
                                                        status =
                                                            if (it) {
                                                                "Camada " +
                                                                    deployed.manifest.id +
                                                                    " removida e backup restaurado."
                                                            } else {
                                                                "Camada não removida."
                                                            }
                                                        reload()
                                                        evidenceRevision +=
                                                            1
                                                    }
                                                    .onFailure {
                                                        status =
                                                            "WINDOWS LAYER REMOVE FAILED: " +
                                                                (
                                                                    it.message
                                                                        ?: it.javaClass
                                                                            .simpleName
                                                                )
                                                    }
                                                busy = false
                                            }
                                        },
                                    ) {
                                        Text(
                                            "Remover " +
                                                deployed.manifest.id +
                                                " / restaurar backup",
                                        )
                                    }
                                }
                        }
                    }
                }
            }
    }


    pendingSuiteExecution?.let {
        (runtime, suiteLayers) ->
        AlertDialog(
            onDismissRequest = {
                pendingSuiteExecution =
                    null
            },
            title = {
                Text(
                    "Executar teste completo?"
                )
            },
            text = {
                Column(
                    modifier =
                        Modifier.verticalScroll(
                            rememberScrollState(),
                        ),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            6.dp,
                        ),
                ) {
                    Text(
                        runtime.manifest.name +
                            " " +
                            runtime.manifest
                                .version,
                    )
                    Text(
                        "O PocketPC executará os diagnósticos em ordem e parará no primeiro bloqueio ou falha.",
                        style =
                            MaterialTheme.typography
                                .bodySmall,
                    )
                    Text(
                        "Inclui Linux, rootfs, Box64, bridge/framebuffer, Wine, processos, rede, áudio/input API e Direct3D. " +
                            "Não instala ferramentas, não aplica DXVK automaticamente e não inicia Roblox.",
                        style =
                            MaterialTheme.typography
                                .bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingSuiteExecution =
                            null
                        busy = true
                        showProbeOutput =
                            true
                        status =
                            "FULL_RUNTIME_DIAGNOSTIC_START"

                        scope.launch {
                            val report =
                                StringBuilder()
                            var completed = 0
                            var stopped = false

                            try {
                                for (
                                    probe in
                                    RuntimeDiagnosticSuite
                                        .orderedProbes
                                ) {
                                    status =
                                        "TESTANDO: " +
                                            probe.label

                                    val plan =
                                        buildProbeInvocationPlan(
                                            runtime =
                                                runtime,
                                            probe =
                                                probe,
                                            layers =
                                                suiteLayers,
                                        )

                                    val executable =
                                        plan.argv
                                            .isNotEmpty() &&
                                            plan.blockers ==
                                            listOf(
                                                ProotExecutionController
                                                    .EXECUTION_APPROVAL_BLOCKER,
                                            )

                                    report.appendLine(
                                        "=== " +
                                            probe.label +
                                            " ===",
                                    )

                                    if (!executable) {
                                        report.appendLine(
                                            "BLOCKED",
                                        )
                                        plan.blockers
                                            .forEach {
                                                report.appendLine(
                                                    "- " +
                                                        it,
                                                )
                                            }
                                        stopped = true
                                        break
                                    }

                                    val result =
                                        if (
                                            probe ==
                                            GuestRuntimeProbe
                                                .DISPLAY_BRIDGE_SMOKE
                                        ) {
                                            displayBridgeProbeController
                                                .execute(
                                                    basePlan =
                                                        plan,
                                                    runtime =
                                                        runtime,
                                                    tools =
                                                        installedTools,
                                                    layers =
                                                        suiteLayers,
                                                    userApproved =
                                                        true,
                                                )
                                                .process
                                        } else {
                                            executionController
                                                .executeOneShot(
                                                    plan =
                                                        plan,
                                                    userApproved =
                                                        true,
                                                )
                                        }

                                    val recorded =
                                        probeEvidenceStore
                                            .recordIfValid(
                                                probe =
                                                    probe,
                                                result =
                                                    result,
                                                runtime =
                                                    runtime,
                                                tools =
                                                    installedTools,
                                                layers =
                                                    suiteLayers,
                                            )
                                    if (recorded) {
                                        evidenceRevision +=
                                            1
                                    }

                                    report.append(
                                        result.state.name,
                                    )
                                    result.exitCode
                                        ?.let {
                                            report.append(
                                                " exit=",
                                            )
                                            report.append(
                                                it,
                                            )
                                        }
                                    report.appendLine()

                                    if (
                                        result.output
                                            .isNotBlank()
                                    ) {
                                        report.appendLine(
                                            result.output
                                                .take(
                                                    6_000,
                                                ),
                                        )
                                        if (
                                            result.outputTruncated ||
                                            result.output.length >
                                            6_000
                                        ) {
                                            report.appendLine(
                                                "[saída truncada]",
                                            )
                                        }
                                    }
                                    result.error
                                        ?.let {
                                            report.appendLine(
                                                "ERROR: " +
                                                    it,
                                            )
                                        }
                                    report.appendLine()

                                    if (!result.passed) {
                                        stopped = true
                                        break
                                    }
                                    completed += 1
                                }

                                reload()

                                report.appendLine(
                                    "=== RESUMO ===",
                                )
                                report.appendLine(
                                    "Etapas concluídas: " +
                                        completed +
                                        "/" +
                                        RuntimeDiagnosticSuite
                                            .orderedProbes
                                            .size,
                                )
                                report.appendLine(
                                    if (stopped) {
                                        "Resultado: PAROU NO PRIMEIRO BLOQUEIO/FALHA."
                                    } else {
                                        "Resultado: TODOS OS DIAGNÓSTICOS DISPONÍVEIS PASSARAM."
                                    },
                                )
                                report.appendLine(
                                    "Roblox não foi iniciado por esta suíte.",
                                )

                                probeOutput =
                                    report.toString()
                                        .take(
                                            48_000,
                                        )
                                status =
                                    if (stopped) {
                                        "FULL_RUNTIME_DIAGNOSTIC_STOPPED " +
                                            completed +
                                            "/" +
                                            RuntimeDiagnosticSuite
                                                .orderedProbes
                                                .size
                                    } else {
                                        "FULL_RUNTIME_DIAGNOSTIC_PASS " +
                                            completed +
                                            "/" +
                                            RuntimeDiagnosticSuite
                                                .orderedProbes
                                                .size
                                    }
                            } catch (
                                cancellation:
                                    CancellationException
                            ) {
                                status =
                                    "FULL_RUNTIME_DIAGNOSTIC_CANCELLED"
                                throw cancellation
                            } catch (
                                failure: Exception
                            ) {
                                report.appendLine(
                                    "SUITE ERROR: " +
                                        (
                                            failure.message
                                                ?: failure
                                                    .javaClass
                                                    .simpleName
                                            ),
                                )
                                probeOutput =
                                    report.toString()
                                        .take(
                                            48_000,
                                        )
                                status =
                                    "FULL_RUNTIME_DIAGNOSTIC_ERROR"
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) {
                    Text(
                        "Executar todos"
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingSuiteExecution =
                            null
                    },
                ) {
                    Text("Cancelar")
                }
            },
        )
    }

    pendingExecution?.let {
        (
            runtime,
            plan,
            probe,
            executedLayers,
        ) ->
        AlertDialog(
            onDismissRequest = {
                pendingExecution = null
            },
            title = {
                Text("Executar probe do runtime?")
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement =
                        Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        runtime.manifest.name +
                            " " +
                            runtime.manifest.version
                    )
                    Text(
                        "Entrypoint: " +
                            "/bin/sh • " + probe.label,
                        style =
                            MaterialTheme.typography
                                .bodySmall,
                    )
                    Text(
                        "Este teste executa comandos de diagnóstico no Linux. " +
                            "A consulta de versões não instala componentes nem inicia Roblox. " +
                            "O processo terá timeout e saída " +
                            "capturada pelo PocketPC.",
                        style =
                            MaterialTheme.typography
                                .bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingExecution = null
                        busy = true
                        status =
                            "R1_EXECUTION_ATTEMPT"
                        scope.launch {
                            val result =
                                if (
                                    probe ==
                                    GuestRuntimeProbe
                                        .DISPLAY_BRIDGE_SMOKE
                                ) {
                                    displayBridgeProbeController
                                        .execute(
                                            basePlan = plan,
                                            runtime = runtime,
                                            tools =
                                                installedTools,
                                            layers =
                                                executedLayers,
                                            userApproved =
                                                true,
                                        )
                                        .process
                                } else {
                                    executionController
                                        .executeOneShot(
                                            plan = plan,
                                            userApproved =
                                                true,
                                        )
                                }
                            val evidenceRecorded =
                                probeEvidenceStore
                                    .recordIfValid(
                                        probe = probe,
                                        result = result,
                                        runtime = runtime,
                                        tools = installedTools,
                                        layers =
                                            executedLayers,
                                    )
                            if (evidenceRecorded) {
                                evidenceRevision += 1
                            }
                            probeOutput = buildString {
                                appendLine("${probe.label}: ${result.state} • exit=${result.exitCode ?: "—"}")
                                appendLine(result.output.take(16_000))
                                if (result.outputTruncated || result.output.length > 16_000) appendLine("[saída truncada]")
                                result.error?.let { appendLine(it) }
                                append("Teste de diagnóstico; compatibilidade com jogos não validada.")
                            }
                            showProbeOutput = true
                            status =
                                buildString {
                                    append(
                                        "R1_"
                                    )
                                    append(
                                        result.state.name
                                    )
                                    result.exitCode
                                        ?.let {
                                            append(
                                                " exit="
                                            )
                                            append(it)
                                        }
                                    if (
                                        result.output
                                            .isNotBlank()
                                    ) {
                                        append(
                                            " • "
                                        )
                                        append(
                                            result.output
                                                .replace(
                                                    "\n",
                                                    " "
                                                )
                                                .take(300)
                                        )
                                    }
                                    result.error
                                        ?.let {
                                            append(
                                                " • "
                                            )
                                            append(it)
                                        }
                                }
                            busy = false
                        }
                    },
                ) {
                    Text("Executar uma vez")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingExecution = null
                    }
                ) {
                    Text("Cancelar")
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StagedRuntimeCard(
    runtime: StagedRuntime,
    enabled: Boolean,
    onAudit: () -> Unit,
    onInstall: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("${runtime.manifest.name} ${runtime.manifest.version}")
            Text(
                "schema ${runtime.manifest.schemaVersion} • ${runtime.manifest.architecture} • ${formatBytes(runtime.stagedBytes)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("archive: ${runtime.manifest.archiveFormat}", style = MaterialTheme.typography.bodySmall)
            Text("sha256: ${runtime.manifest.rootfsSha256.take(16)}…", style = MaterialTheme.typography.bodySmall)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onAudit, enabled = enabled) { Text("Auditar") }
                if (onInstall != null) {
                    TextButton(onClick = onInstall, enabled = enabled) { Text("Extrair dados") }
                }
                TextButton(onClick = onRemove, enabled = enabled) { Text("Remover") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstalledRuntimeCard(
    runtime: InstalledRuntime,
    enabled: Boolean,
    executionReady: Boolean,
    onRunSuite: (() -> Unit)?,
    onRunProbe: (() -> Unit)?,
    onPrepareLinks: (() -> Unit)?,
    onVerifyLinks: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("${runtime.manifest.name} ${runtime.manifest.version}")
            Text(
                "${runtime.stats.entries} entradas • ${runtime.stats.regularFiles} arquivos • " +
                    "${runtime.stats.linksRecorded} links",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Extraído: ${formatBytes(runtime.stats.extractedBytes)}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Links: ${when {
                    runtime.stats.linksRecorded == 0 -> "NÃO NECESSÁRIOS"
                    runtime.linksPrepared -> "LINKS_PREPARED"
                    else -> "METADATA_ONLY"
                }}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "R1 Linux: " +
                    if (executionReady) {
                        "PRONTO PARA CONFIRMAÇÃO"
                    } else {
                        "BLOQUEADO"
                    },
                style =
                    MaterialTheme.typography
                        .labelSmall,
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onRunSuite != null) {
                    Button(
                        onClick = onRunSuite,
                        enabled = enabled,
                    ) {
                        Text("Teste completo")
                    }
                }
                if (onRunProbe != null) {
                    TextButton(
                        onClick = onRunProbe,
                        enabled = enabled,
                    ) {
                        Text("Probe individual")
                    }
                }
                if (onPrepareLinks != null) {
                    TextButton(onClick = onPrepareLinks, enabled = enabled) { Text("Preparar links") }
                }
                if (onVerifyLinks != null) {
                    TextButton(onClick = onVerifyLinks, enabled = enabled) { Text("Verificar links") }
                }
                TextButton(onClick = onRemove, enabled = enabled) { Text("Remover") }
            }
        }
    }
}

@Composable
private fun RuntimeDetailRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
