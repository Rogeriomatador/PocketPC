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
import dev.pocketpc.core.runtime.RuntimeManifestValidator
import dev.pocketpc.core.runtime.RuntimePackageManager
import dev.pocketpc.core.runtime.StagedRuntime
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuntimeApp(
    manager: RuntimePackageManager,
    installer: RuntimeInstallManager,
    linkManager: RootfsLinkManager,
    nativeHost: NativeHostStatus,
    substrate: ExecutionSubstrateStatus,
    target: PcApplicationTarget?,
    onClearTarget: () -> Unit,
    manifestUri: String?,
    rootfsUri: String?,
    onChooseManifest: () -> Unit,
    onChooseRootfs: () -> Unit,
    onClearSelection: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val appContext =
        LocalContext.current.applicationContext
    val bindPlanner =
        remember(appContext) {
            RuntimeBindPlanner(appContext)
        }
    val executionController =
        remember {
            ProotExecutionController()
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
            Pair<
                InstalledRuntime,
                ProotInvocationPlan
            >?
        >(null)
    }
    var staged by remember { mutableStateOf<List<StagedRuntime>>(emptyList()) }
    var installed by remember { mutableStateOf<List<InstalledRuntime>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var showPcRuntimeStages by rememberSaveable {
        mutableStateOf(false)
    }

    suspend fun reload() {
        staged = manager.discover()
        installed = installer.discover()
    }

    LaunchedEffect(Unit) { reload() }

        val pcReadiness =
            PcRuntimeReadinessProbe.assess(
                nativeHost = nativeHost,
                substrate = substrate,
                installedRuntimeCount =
                    installed.size,
                preparedRuntimeCount =
                    installed.count { runtime ->
                        RootfsExecutionReadinessProbe
                            .assess(runtime)
                            .ready
                    },
            )


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
                    Text("Os testes ficam disponíveis quando o PRoot e o rootfs estiverem prontos.",
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
                val invocationPlan by produceState(
                    initialValue = ProotInvocationPlan(false, emptyList(), emptyMap(), listOf("BIND_PLAN_PREPARING")),
                    key1 = runtime,
                    key2 = substrate,
                    key3 = selectedProbe,
                ) {
                    value = withContext(Dispatchers.IO) {
                    runCatching {
                        ProotInvocationPlanner.buildProbe(
                            runtime = runtime,
                            substrate = substrate,
                            probe = selectedProbe,
                            binds =
                                bindPlanner
                                    .base(runtime),
                            allowedHostRoots =
                                bindPlanner
                                    .allowedHostRoots(),
                        )
                    }.getOrElse { failure ->
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
                                            )
                                ),
                        )
                    }
                    }
                }
                val executionRequestReady =
                    invocationPlan.argv.isNotEmpty() &&
                        invocationPlan.blockers ==
                        listOf(
                            ProotExecutionController
                                .EXECUTION_APPROVAL_BLOCKER
                        )

                InstalledRuntimeCard(
                    runtime = runtime,
                    enabled = !busy,
                    executionReady =
                        executionRequestReady,
                    onRunProbe =
                        if (
                            executionRequestReady &&
                            !busy
                        ) {
                            {
                                pendingExecution =
                                    runtime to
                                        invocationPlan
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
            }
    }

    pendingExecution?.let {
        (runtime, plan) ->
        AlertDialog(
            onDismissRequest = {
                pendingExecution = null
            },
            title = {
                Text("Executar probe ARM64?")
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
                            "/bin/sh • " + selectedProbe.label,
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
                                executionController
                                    .executeOneShot(
                                        plan = plan,
                                        userApproved =
                                            true,
                                    )
                            probeOutput = buildString {
                                appendLine("${selectedProbe.label}: ${result.state} • exit=${result.exitCode ?: "—"}")
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
                if (onRunProbe != null) {
                    Button(
                        onClick = onRunProbe,
                        enabled = enabled,
                    ) {
                        Text("Probe ARM64")
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
