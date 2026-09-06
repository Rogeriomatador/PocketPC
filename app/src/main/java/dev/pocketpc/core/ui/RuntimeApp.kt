package dev.pocketpc.core.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketpc.core.runtime.NativeHostStatus
import dev.pocketpc.core.runtime.RuntimePackageManager
import dev.pocketpc.core.runtime.StagedRuntime
import kotlinx.coroutines.launch

@Composable
fun RuntimeApp(
    manager: RuntimePackageManager,
    nativeHost: NativeHostStatus,
    manifestUri: String?,
    rootfsUri: String?,
    onChooseManifest: () -> Unit,
    onChooseRootfs: () -> Unit,
    onClearSelection: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var runtimes by remember { mutableStateOf<List<StagedRuntime>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch { runtimes = manager.discover() }
    }

    LaunchedEffect(Unit) { runtimes = manager.discover() }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Runtimes", style = MaterialTheme.typography.titleMedium)

        Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Runtime Host empacotado", style = MaterialTheme.typography.titleSmall)
                ValueRow("Native host", if (nativeHost.loaded) "LOADED" else "FAILED")
                Text(nativeHost.probe, style = MaterialTheme.typography.bodySmall)
                Text("nativeLibraryDir: ${nativeHost.nativeLibraryDir}", style = MaterialTheme.typography.bodySmall)
                Text("Vulkan native probe", style = MaterialTheme.typography.titleSmall)
                Text(nativeHost.graphicsProbe, style = MaterialTheme.typography.bodySmall)
                Text(
                    "O rootfs permanece como dados verificados; execução Linux ainda não está habilitada.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Text("Staging de rootfs", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onChooseManifest, enabled = !busy) {
                Text(if (manifestUri == null) "Escolher manifesto" else "Manifesto ✓")
            }
            OutlinedButton(onClick = onChooseRootfs, enabled = !busy) {
                Text(if (rootfsUri == null) "Escolher rootfs" else "Rootfs ✓")
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
                                runtimes = manager.discover()
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
                Text("Limpar seleção")
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
        Text("Runtimes verificados em staging", style = MaterialTheme.typography.titleSmall)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (runtimes.isEmpty()) {
                item {
                    Text(
                        "Nenhum rootfs verificado ainda. Isso é esperado na primeira execução.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            items(
                items = runtimes,
                key = { "${it.manifest.id}:${it.manifest.version}" },
            ) { runtime ->
                RuntimeCard(
                    runtime = runtime,
                    enabled = !busy,
                    onAudit = {
                        busy = true
                        status = "Recalculando SHA-256 de ${runtime.manifest.name}…"
                        scope.launch {
                            val audit = manager.audit(runtime)
                            status = if (audit.valid) {
                                "AUDIT_OK: ${runtime.manifest.name} ${runtime.manifest.version}"
                            } else {
                                audit.message
                            }
                            busy = false
                        }
                    },
                    onRemove = {
                        busy = true
                        scope.launch {
                            val removed = manager.remove(runtime)
                            status = if (removed) "Staging removido." else "Não foi possível remover staging."
                            runtimes = manager.discover()
                            busy = false
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun RuntimeCard(
    runtime: StagedRuntime,
    enabled: Boolean,
    onAudit: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text("${runtime.manifest.name} ${runtime.manifest.version}")
                    Text(
                        "${runtime.manifest.architecture} • ${formatBytes(runtime.stagedBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = onAudit, enabled = enabled) { Text("Auditar") }
                TextButton(onClick = onRemove, enabled = enabled) { Text("Remover") }
            }
            Text("id: ${runtime.manifest.id}", style = MaterialTheme.typography.bodySmall)
            Text("entrypoint: ${runtime.manifest.entrypoint}", style = MaterialTheme.typography.bodySmall)
            Text("license: ${runtime.manifest.license}", style = MaterialTheme.typography.bodySmall)
            Text("sha256: ${runtime.manifest.rootfsSha256.take(16)}…", style = MaterialTheme.typography.bodySmall)
            Text("Estado: STAGED_VERIFIED (não executável ainda)", style = MaterialTheme.typography.labelSmall)
        }
    }
}
