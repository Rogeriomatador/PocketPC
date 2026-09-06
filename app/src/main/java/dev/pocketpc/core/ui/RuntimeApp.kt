package dev.pocketpc.core.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketpc.core.runtime.ExecutionSubstrateStatus
import dev.pocketpc.core.runtime.InstalledRuntime
import dev.pocketpc.core.runtime.NativeHostStatus
import dev.pocketpc.core.runtime.RootfsLinkManager
import dev.pocketpc.core.runtime.RuntimeInstallManager
import dev.pocketpc.core.runtime.RuntimeManifestValidator
import dev.pocketpc.core.runtime.RuntimePackageManager
import dev.pocketpc.core.runtime.StagedRuntime
import kotlinx.coroutines.launch

@Composable
fun RuntimeApp(
    manager: RuntimePackageManager,
    installer: RuntimeInstallManager,
    linkManager: RootfsLinkManager,
    nativeHost: NativeHostStatus,
    substrate: ExecutionSubstrateStatus,
    manifestUri: String?,
    rootfsUri: String?,
    onChooseManifest: () -> Unit,
    onChooseRootfs: () -> Unit,
    onClearSelection: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var staged by remember { mutableStateOf<List<StagedRuntime>>(emptyList()) }
    var installed by remember { mutableStateOf<List<InstalledRuntime>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        staged = manager.discover()
        installed = installer.discover()
    }

    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Runtimes", style = MaterialTheme.typography.titleMedium)

        Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text("Execution substrate", style = MaterialTheme.typography.titleSmall)
                ValueRow("Native host", if (nativeHost.loaded) "LOADED" else "FAILED")
                ValueRow("Substrate", substrate.state)
                ValueRow(
                    "PRoot components",
                    if (substrate.prootReady) "PRESENT / UNVALIDATED" else "NOT BUNDLED",
                )
                Text(nativeHost.probe, style = MaterialTheme.typography.bodySmall)
                Text(nativeHost.graphicsProbe, style = MaterialTheme.typography.bodySmall)

                if (!substrate.prootReady) {
                    Text(
                        "Nenhum Linux é marcado como executável: PRoot/loader continuam fora do APK.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Text("Importar rootfs", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onChooseManifest, enabled = !busy) {
                Text(if (manifestUri == null) "Manifesto" else "Manifesto ✓")
            }
            OutlinedButton(onClick = onChooseRootfs, enabled = !busy) {
                Text(if (rootfsUri == null) "Rootfs" else "Rootfs ✓")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
                InstalledRuntimeCard(
                    runtime = runtime,
                    enabled = !busy,
                    executionReady = substrate.prootReady,
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
    }
}

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

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onAudit, enabled = enabled) { Text("Auditar") }
                if (onInstall != null) {
                    TextButton(onClick = onInstall, enabled = enabled) { Text("Extrair dados") }
                }
                TextButton(onClick = onRemove, enabled = enabled) { Text("Remover") }
            }
        }
    }
}

@Composable
private fun InstalledRuntimeCard(
    runtime: InstalledRuntime,
    enabled: Boolean,
    executionReady: Boolean,
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
                "Execução Linux: ${if (executionReady) "SUBSTRATE PRESENT / EXECUTOR DESLIGADO" else "BLOQUEADA"}",
                style = MaterialTheme.typography.labelSmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
