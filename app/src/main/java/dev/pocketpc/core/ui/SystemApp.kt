package dev.pocketpc.core.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketpc.core.runtime.ExecutionSubstrateStatus
import dev.pocketpc.core.runtime.NativeHostStatus
import dev.pocketpc.core.system.SystemSnapshot

@Composable
fun SystemApp(
    snapshot: SystemSnapshot,
    storageConfigured: Boolean,
    nativeHost: NativeHostStatus,
    substrate: ExecutionSubstrateStatus,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Sistema", style = MaterialTheme.typography.titleMedium)
        Section("PocketPC") {
            ValueRow("Versão", "0.1.0-alpha6")
            ValueRow("Desktop shell", "IMPLEMENTED")
            ValueRow("Arquivos SAF", if (storageConfigured) "IMPLEMENTED / CONFIGURED" else "IMPLEMENTED")
            ValueRow("Local Android shell", "IMPLEMENTED")
            ValueRow("Runtime staging", "IMPLEMENTED")
            ValueRow("Safe rootfs install", "IMPLEMENTED / DATA ONLY")
            ValueRow("Native Runtime Host", if (nativeHost.loaded) "IMPLEMENTED / LOADED" else "IMPLEMENTED / LOAD FAILED")
            ValueRow("Execution substrate", substrate.state)
            ValueRow("Native Vulkan probe", "IMPLEMENTED / NOT DEVICE VALIDATED")
            ValueRow("Bind/env policy", "IMPLEMENTED / EXECUTION DISABLED")
            ValueRow("Process supervisor", "IMPLEMENTED / ONE-SHOT FOUNDATION")
            ValueRow("Linux ARM execution", "DESIGN / NOT IMPLEMENTED")
            ValueRow("Windows x86/x64", "DESIGN / PLANNED")
            ValueRow("vGPU", "DESIGN / PLANNED")
        }

        Section("Dispositivo") {
            ValueRow("Fabricante", snapshot.manufacturer)
            ValueRow("Modelo", snapshot.model)
            ValueRow("Android", "${snapshot.androidVersion} (API ${snapshot.apiLevel})")
            ValueRow("ABIs", snapshot.abis.joinToString())
            ValueRow("CPU lógica", snapshot.cpuCores.toString())
            ValueRow("OpenGL ES", snapshot.glEsVersion)
            ValueRow(
                "Partição /data",
                "${formatBytes(snapshot.internalFreeBytes)} livres / ${formatBytes(snapshot.internalTotalBytes)}",
            )
        }

        Section("Runtime host") {
            Text(nativeHost.probe, style = MaterialTheme.typography.bodySmall)
            Text("nativeLibraryDir: ${nativeHost.nativeLibraryDir}", style = MaterialTheme.typography.bodySmall)
            Text("substrate: ${substrate.state}", style = MaterialTheme.typography.bodySmall)
            substrate.components.forEach { component ->
                Text(
                    "${component.fileName}: exists=${component.exists}, exec=${component.executable}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Section("Vulkan — PackageManager") {
            if (snapshot.vulkanFeatures.isEmpty()) {
                Text("Nenhuma feature Vulkan foi exposta pelo PackageManager.", style = MaterialTheme.typography.bodySmall)
            } else {
                snapshot.vulkanFeatures.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        Section("Vulkan — NDK probe") {
            Text(nativeHost.graphicsProbe, style = MaterialTheme.typography.bodySmall)
            Text(
                "Este probe apenas enumera capacidades. Ele não renderiza, não mede FPS e não valida uma vGPU.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Section(title: String, body: @Composable ColumnScope.() -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = body)
}

@Composable
internal fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(0.42f))
        Text(value, modifier = Modifier.weight(0.58f))
    }
}
