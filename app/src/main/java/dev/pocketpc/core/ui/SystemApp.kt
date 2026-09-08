package dev.pocketpc.core.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketpc.core.BuildConfig
import dev.pocketpc.core.desktop.DesktopCapabilitySnapshot
import dev.pocketpc.core.runtime.DeviceEvidenceCollector
import dev.pocketpc.core.runtime.DeviceEvidenceReport
import dev.pocketpc.core.runtime.EvidenceBundleManager
import dev.pocketpc.core.runtime.EvidenceBundleReport
import dev.pocketpc.core.runtime.ExecutionSubstrateStatus
import dev.pocketpc.core.runtime.NativeHostStatus
import dev.pocketpc.core.system.SystemSnapshot
import kotlinx.coroutines.launch
import java.util.Locale

private enum class PcInfoTab(
    val label: String,
) {
    OVERVIEW("Visão geral"),
    HARDWARE("Hardware"),
    DESKTOP("Desktop"),
    UPDATES("Atualizações"),
    RESEARCH("Pesquisa"),
    DIAGNOSTICS("Diagnóstico"),
}

@Composable
fun SystemApp(
    snapshot: SystemSnapshot,
    storageConfigured: Boolean,
    nativeHost: NativeHostStatus,
    substrate: ExecutionSubstrateStatus,
    desktopCapabilities: DesktopCapabilitySnapshot,
) {
    val context =
        androidx.compose.ui.platform.LocalContext.current
            .applicationContext
    val scope = rememberCoroutineScope()

    var tabName by rememberSaveable {
        mutableStateOf(PcInfoTab.OVERVIEW.name)
    }
    val tab =
        PcInfoTab.entries.firstOrNull {
            it.name == tabName
        } ?: PcInfoTab.OVERVIEW

    var evidence by remember {
        mutableStateOf<DeviceEvidenceReport?>(null)
    }
    var bundle by remember {
        mutableStateOf<EvidenceBundleReport?>(null)
    }
    var evidenceStatus by remember {
        mutableStateOf<String?>(null)
    }
    var evidenceBusy by remember {
        mutableStateOf(false)
    }

    val exportBundle =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(
                "application/zip"
            ),
        ) { uri ->
            val current = bundle
            if (uri != null && current != null) {
                evidenceBusy = true
                evidenceStatus =
                    "EVIDENCE_BUNDLE_EXPORTING"
                scope.launch {
                    EvidenceBundleManager.exportToUri(
                        context = context,
                        bundle = current,
                        destination = uri,
                    )
                        .onSuccess {
                            evidenceStatus =
                                "EVIDENCE_BUNDLE_EXPORTED"
                        }
                        .onFailure { error ->
                            evidenceStatus =
                                "EVIDENCE_BUNDLE_EXPORT_FAILED: " +
                                    (
                                        error.message
                                            ?: error.javaClass.simpleName
                                    )
                        }
                    evidenceBusy = false
                }
            }
        }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        Text("Este PC", Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.titleMedium)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement =
                Arrangement.spacedBy(6.dp),
        ) {
            PcInfoTab.entries.forEach { item ->
                FilterChip(
                    selected = tab == item,
                    onClick = {
                        tabName = item.name
                    },
                    label = {
                        Text(
                            item.label,
                            fontSize = 10.sp,
                        )
                    },
                )
            }
        }

        HorizontalDivider()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(12.dp),
            verticalArrangement =
                Arrangement.spacedBy(10.dp),
        ) {
            when (tab) {
                PcInfoTab.OVERVIEW -> {
                    PcHeader(snapshot)
                    PcSummaryCards(snapshot)
                    OverviewTab(
                        snapshot = snapshot,
                        storageConfigured =
                            storageConfigured,
                        nativeHost = nativeHost,
                        substrate = substrate,
                    )

                }
                PcInfoTab.HARDWARE ->
                    HardwareTab(snapshot)

                PcInfoTab.DESKTOP ->
                    DesktopTab(
                        snapshot = snapshot,
                        capabilities =
                            desktopCapabilities,
                    )

                PcInfoTab.UPDATES ->
                    UpdateCenterApp()

                PcInfoTab.RESEARCH ->
                    ResearchLabApp()

                PcInfoTab.DIAGNOSTICS ->
                    DiagnosticsTab(
                        context = context,
                        scope = scope,
                        snapshot = snapshot,
                        nativeHost = nativeHost,
                        substrate = substrate,
                        evidence = evidence,
                        bundle = bundle,
                        evidenceStatus =
                            evidenceStatus,
                        evidenceBusy =
                            evidenceBusy,
                        onEvidenceBusy = {
                            evidenceBusy = it
                        },
                        onEvidence = {
                            evidence = it
                        },
                        onBundle = {
                            bundle = it
                        },
                        onStatus = {
                            evidenceStatus = it
                        },
                        onExport = { report ->
                            val revision =
                                report.buildIdentity
                                    .sourceRevision
                                    .let {
                                        if (
                                            report.buildIdentity
                                                .sourceRevisionPinned
                                        ) {
                                            it.take(12)
                                        } else {
                                            "local"
                                        }
                                    }
                            exportBundle.launch(
                                "PocketPC-" +
                                    "${report.buildIdentity.versionName}-" +
                                    "$revision-evidence.zip"
                            )
                        },
                    )
            }
        }
    }
}

@Composable
private fun PcHeader(
    snapshot: SystemSnapshot,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = 14.dp,
                vertical = 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                "Este PC",
                style =
                    MaterialTheme.typography.headlineSmall,
            )
            Text(
                "${snapshot.manufacturer} ${snapshot.model} • " +
                    "PocketPC ${BuildConfig.VERSION_NAME}",
                style =
                    MaterialTheme.typography.bodySmall,
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant,
            )
        }

        AssistChip(
            onClick = {},
            label = {
                Text(
                    snapshot.networkTransport,
                    fontSize = 10.sp,
                )
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PcSummaryCards(
    snapshot: SystemSnapshot,
) {
    val usedStorage =
        (
            snapshot.internalTotalBytes -
                snapshot.internalFreeBytes
        ).coerceAtLeast(0L)
    val usedRam =
        (
            snapshot.totalRamBytes -
                snapshot.availableRamBytes
        ).coerceAtLeast(0L)

    FlowRow(
        maxItemsInEachRow = if (LocalAppViewport.current.widthDp < 500f) 2 else 4,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = 10.dp,
                vertical = 4.dp,
            ),
        horizontalArrangement =
            Arrangement.spacedBy(8.dp),
    ) {
        PcStatCard(
            title = "Processador",
            value = snapshot.socModel,
            detail =
                "${snapshot.cpuCores} núcleos lógicos",
            modifier = Modifier.weight(1f),
        )
        PcStatCard(
            title = "Memória",
            value = formatBytes(
                snapshot.totalRamBytes
            ),
            detail =
                "${formatBytes(usedRam)} em uso",
            modifier = Modifier.weight(1f),
        )
        PcStatCard(
            title = "Armazenamento",
            value = formatBytes(
                snapshot.internalTotalBytes
            ),
            detail =
                "${formatBytes(usedStorage)} em uso",
            modifier = Modifier.weight(1f),
        )
        PcStatCard(
            title = "Tela",
            value =
                "${snapshot.displayWidthPx}×" +
                    "${snapshot.displayHeightPx}",
            detail =
                String.format(
                    Locale.ROOT,
                    "%.0f Hz",
                    snapshot.refreshRateHz,
                ),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PcStatCard(
    title: String,
    value: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .heightIn(min = 76.dp),
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement =
                Arrangement.spacedBy(3.dp),
        ) {
            Text(
                title,
                fontSize = 10.sp,
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant,
            )
            Text(
                value,
                style =
                    MaterialTheme.typography.titleSmall,
                maxLines = 2,
            )
            Text(
                detail,
                fontSize = 9.sp,
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OverviewTab(
    snapshot: SystemSnapshot,
    storageConfigured: Boolean,
    nativeHost: NativeHostStatus,
    substrate: ExecutionSubstrateStatus,
) {
    InfoSection("Sistema") {
        ValueRow(
            "PocketPC",
            BuildConfig.VERSION_NAME,
        )
        ValueRow(
            "Android",
            "${snapshot.androidVersion} " +
                "(API ${snapshot.apiLevel})",
        )
        ValueRow(
            "Patch de segurança",
            snapshot.securityPatch,
        )
        ValueRow(
            "Kernel",
            snapshot.kernelVersion,
        )
    }

    InfoSection("Unidades do PocketPC") {
        ValueRow(
            "C:  PocketPC System",
            "Sistema rápido e privado",
        )
        ValueRow(
            "C:  Uso",
            "runtime • cache • temporários • índices",
        )
        ValueRow(
            "P:  PocketDrive",
            if (storageConfigured) {
                "Conectado • persistente"
            } else {
                "Não conectado"
            },
        )
        ValueRow(
            "P:  Uso",
            "Desktop • Documentos • Downloads • Apps • Jogos",
        )
        Text(
            "C: e P: são volumes lógicos do PocketPC. P: usa a pasta " +
                "autorizada pelo Android e permanece separado do ciclo do APK.",
            style =
                MaterialTheme.typography.bodySmall,
            color =
                MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    InfoSection("Recursos") {
        ValueRow(
            "Explorador",
            if (storageConfigured) {
                "Conectado"
            } else {
                "Aguardando pasta"
            },
        )
        ValueRow(
            "Navegador",
            "Abas + modo desktop + downloads",
        )
        ValueRow(
            "Entrada desktop",
            "Mouse • teclado • gamepad",
        )
        ValueRow(
            "Runtime nativo",
            if (nativeHost.loaded) {
                "Carregado"
            } else {
                "Indisponível"
            },
        )
        ValueRow(
            "Linux ARM",
            if (
                substrate.artifactContractApproved &&
                substrate.policyDigestsVerified &&
                substrate.artifactIntegrityVerified
            ) {
                "Staging aprovado"
            } else {
                "Bloqueado por gates"
            },
        )
    }

    InfoSection("Unidades") {
        ValueRow(
            "C: PocketPC System",
            "Privado • runtime/cache/sistema",
        )
        ValueRow(
            "P: PocketDrive",
            if (storageConfigured) {
                "Conectado • dados persistentes"
            } else {
                "Não conectado"
            },
        )
        Text(
            "C: e P: são volumes lógicos do PocketPC. " +
                "Se P: estiver no armazenamento interno do telefone, " +
                "eles podem compartilhar o mesmo chip físico, mas " +
                "continuam separados por finalidade e política de acesso.",
            style =
                MaterialTheme.typography.bodySmall,
            color =
                MaterialTheme.colorScheme
                    .onSurfaceVariant,
        )
    }

    InfoSection("Energia e rede") {
        ValueRow(
            "Bateria",
            "${snapshot.batteryPercent}%",
        )
        ValueRow(
            "Temperatura",
            snapshot.batteryTemperatureC
                ?.let {
                    String.format(
                        Locale.ROOT,
                        "%.1f °C",
                        it,
                    )
                }
                ?: "indisponível",
        )
        ValueRow(
            "Rede",
            snapshot.networkTransport,
        )
    }
}

@Composable
private fun HardwareTab(
    snapshot: SystemSnapshot,
) {
    InfoSection("Processador") {
        ValueRow(
            "SoC",
            snapshot.socModel,
        )
        ValueRow(
            "Fabricante do SoC",
            snapshot.socManufacturer,
        )
        ValueRow(
            "Núcleos lógicos",
            snapshot.cpuCores.toString(),
        )
        ValueRow(
            "Hardware",
            snapshot.hardware,
        )
        ValueRow(
            "Board",
            snapshot.board,
        )
        ValueRow(
            "Arquitetura",
            snapshot.abis.joinToString(),
        )
    }

    InfoSection("Memória") {
        ValueRow(
            "RAM total",
            formatBytes(snapshot.totalRamBytes),
        )
        ValueRow(
            "RAM disponível",
            formatBytes(
                snapshot.availableRamBytes
            ),
        )
    }

    InfoSection("Armazenamento interno") {
        ValueRow(
            "Total",
            formatBytes(
                snapshot.internalTotalBytes
            ),
        )
        ValueRow(
            "Livre",
            formatBytes(
                snapshot.internalFreeBytes
            ),
        )
    }

    InfoSection("Tela") {
        ValueRow(
            "Resolução",
            "${snapshot.displayWidthPx} × " +
                "${snapshot.displayHeightPx}",
        )
        ValueRow(
            "Densidade",
            "${snapshot.densityDpi} dpi",
        )
        ValueRow(
            "Atualização",
            String.format(
                Locale.ROOT,
                "%.1f Hz",
                snapshot.refreshRateHz,
            ),
        )
    }

    InfoSection("Gráficos") {
        ValueRow(
            "OpenGL ES",
            snapshot.glEsVersion,
        )
        ValueRow(
            "Vulkan",
            if (snapshot.vulkanFeatures.isEmpty()) {
                "Não anunciado"
            } else {
                "${snapshot.vulkanFeatures.size} feature(s)"
            },
        )
        snapshot.vulkanFeatures.forEach {
            Text(
                it,
                style =
                    MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DesktopTab(
    snapshot: SystemSnapshot,
    capabilities: DesktopCapabilitySnapshot,
) {
    InfoSection("Modo desktop") {
        ValueRow(
            "Landscape",
            "Ativo pelo PocketPC",
        )
        ValueRow(
            "Freeform Android",
            if (
                capabilities.freeformWindowManagement
            ) {
                "SUPPORTED"
            } else {
                "NOT ADVERTISED"
            },
        )
        ValueRow(
            "Activities em display secundário",
            if (
                capabilities.secondaryDisplayActivities
            ) {
                "SUPPORTED"
            } else {
                "NOT ADVERTISED"
            },
        )
        ValueRow(
            "Android PC hardware type",
            if (capabilities.pcHardwareType) {
                "ADVERTISED"
            } else {
                "NOT ADVERTISED"
            },
        )
    }

    InfoSection("Tela principal") {
        ValueRow(
            "Resolução",
            "${snapshot.displayWidthPx} × " +
                "${snapshot.displayHeightPx}",
        )
        ValueRow(
            "Atualização",
            String.format(
                Locale.ROOT,
                "%.1f Hz",
                snapshot.refreshRateHz,
            ),
        )
    }

    InfoSection("Displays conectados") {
        ValueRow(
            "Externos",
            capabilities.externalDisplayCount
                .toString(),
        )
        ValueRow(
            "Presentation",
            capabilities.presentationDisplayCount
                .toString(),
        )

        capabilities.externalDisplays.forEach {
            display ->
            ValueRow(
                "#${display.displayId} ${display.name}",
                "${display.widthPx}×" +
                    "${display.heightPx} • " +
                    String.format(
                        Locale.ROOT,
                        "%.1f Hz",
                        display.refreshRateHz,
                    ),
            )
        }
    }
}

@Composable
private fun DiagnosticsTab(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    snapshot: SystemSnapshot,
    nativeHost: NativeHostStatus,
    substrate: ExecutionSubstrateStatus,
    evidence: DeviceEvidenceReport?,
    bundle: EvidenceBundleReport?,
    evidenceStatus: String?,
    evidenceBusy: Boolean,
    onEvidenceBusy: (Boolean) -> Unit,
    onEvidence: (DeviceEvidenceReport?) -> Unit,
    onBundle: (EvidenceBundleReport?) -> Unit,
    onStatus: (String?) -> Unit,
    onExport: (EvidenceBundleReport) -> Unit,
) {
    InfoSection("Evidence do dispositivo") {
        Text(
            "Teste local e não destrutivo. Não executa PRoot e não requer root.",
            style =
                MaterialTheme.typography.bodySmall,
        )

        Row(
            horizontalArrangement =
                Arrangement.spacedBy(8.dp),
        ) {
            Button(
                enabled = !evidenceBusy,
                onClick = {
                    onEvidenceBusy(true)
                    onEvidence(null)
                    onBundle(null)
                    onStatus(
                        "DEVICE_EVIDENCE_RUNNING"
                    )

                    scope.launch {
                        DeviceEvidenceCollector
                            .collectAndPersist(
                                context = context,
                                nativeHost = nativeHost,
                                substrate = substrate,
                            )
                            .onSuccess { report ->
                                onEvidence(report)
                                onStatus(
                                    if (
                                        report.filesystem
                                            .hostCriticalPassed
                                    ) {
                                        "DEVICE_HOST_FILESYSTEM_PASS"
                                    } else {
                                        "DEVICE_HOST_FILESYSTEM_FAIL"
                                    }
                                )

                                EvidenceBundleManager
                                    .create(
                                        context,
                                        report,
                                    )
                                    .onSuccess {
                                        created ->
                                        onBundle(created)
                                        onStatus(
                                            "DEVICE_EVIDENCE_BUNDLE_READY"
                                        )
                                    }
                                    .onFailure { error ->
                                        onStatus(
                                            "EVIDENCE_BUNDLE_CREATE_FAILED: " +
                                                (
                                                    error.message
                                                        ?: error.javaClass.simpleName
                                                )
                                        )
                                    }
                            }
                            .onFailure { error ->
                                onStatus(
                                    "DEVICE_EVIDENCE_FAILED: " +
                                        (
                                            error.message
                                                ?: error.javaClass.simpleName
                                        )
                                )
                            }
                        onEvidenceBusy(false)
                    }
                },
            ) {
                Text("Executar teste")
            }

            OutlinedButton(
                enabled =
                    !evidenceBusy && bundle != null,
                onClick = {
                    bundle?.let(onExport)
                },
            ) {
                Text("Exportar bundle")
            }
        }

        if (evidenceBusy) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth()
            )
        }

        evidenceStatus?.let {
            Text(
                it,
                style =
                    MaterialTheme.typography.bodySmall,
            )
        }

        evidence?.let { report ->
            ValueRow(
                "Symlink relativo",
                passLabel(
                    report.filesystem
                        .relativeSymlink.passed
                ),
            )
            ValueRow(
                "Symlink absoluto",
                passLabel(
                    report.filesystem
                        .absoluteSymlink.passed
                ),
            )
            ValueRow(
                "Hardlink",
                passLabel(
                    report.filesystem.hardlink.passed
                ),
            )
            ValueRow(
                "Host filesystem",
                if (
                    report.filesystem
                        .hostCriticalPassed
                ) {
                    "PASS"
                } else {
                    "FAIL / REVIEW"
                },
            )
            ValueRow(
                "Linux links",
                if (
                    report.filesystem
                        .runtimeLinkSemanticsReady
                ) {
                    "READY"
                } else {
                    "BLOCKED"
                },
            )
        }

        bundle?.let {
            ValueRow(
                "Bundle entries",
                it.entryCount.toString(),
            )
            Text(
                "SHA-256: ${it.bundleSha256}",
                style =
                    MaterialTheme.typography.bodySmall,
            )
        }
    }

    InfoSection("Runtime host") {
        ValueRow(
            "Native host",
            if (nativeHost.loaded) {
                "LOADED"
            } else {
                "LOAD FAILED"
            },
        )
        ValueRow(
            "Execution substrate",
            substrate.state,
        )
        ValueRow(
            "Approval",
            if (
                substrate.artifactContractApproved
            ) {
                "APPROVED"
            } else {
                "LOCKED"
            },
        )
        Text(
            nativeHost.probe,
            style =
                MaterialTheme.typography.bodySmall,
        )
    }

    InfoSection("Gráficos / NDK") {
        ValueRow(
            "OpenGL ES",
            snapshot.glEsVersion,
        )
        Text(
            nativeHost.graphicsProbe,
            style =
                MaterialTheme.typography.bodySmall,
        )
        Text(
            "O probe enumera capacidades. Não valida vGPU nem mede FPS.",
            style =
                MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun InfoSection(
    title: String,
    body: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement =
                Arrangement.spacedBy(6.dp),
        ) {
            Text(
                title,
                style =
                    MaterialTheme.typography.titleSmall,
            )
            HorizontalDivider()
            body()
        }
    }
}

private fun passLabel(
    value: Boolean,
): String =
    if (value) "PASS" else "FAIL"

@Composable
internal fun ValueRow(
    label: String,
    value: String,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement =
            Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            modifier = Modifier.weight(0.42f),
            style =
                MaterialTheme.typography.bodySmall,
        )
        Text(
            value,
            modifier = Modifier.weight(0.58f),
            style =
                MaterialTheme.typography.bodySmall,
        )
    }
}
