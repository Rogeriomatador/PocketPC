package dev.pocketpc.core.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.pocketpc.core.runtime.DeviceEvidenceCollector
import dev.pocketpc.core.runtime.DeviceEvidenceReport
import dev.pocketpc.core.runtime.EvidenceBundleManager
import dev.pocketpc.core.runtime.EvidenceBundleReport
import dev.pocketpc.core.runtime.ExecutionSubstrateStatus
import dev.pocketpc.core.runtime.NativeHostStatus
import dev.pocketpc.core.system.SystemSnapshot
import kotlinx.coroutines.launch

@Composable
fun SystemApp(
    snapshot: SystemSnapshot,
    storageConfigured: Boolean,
    nativeHost: NativeHostStatus,
    substrate: ExecutionSubstrateStatus,
) {
    val localContext = LocalContext.current
    val context = localContext.applicationContext
    val scope = rememberCoroutineScope()

    var evidence by remember { mutableStateOf<DeviceEvidenceReport?>(null) }
    var bundle by remember { mutableStateOf<EvidenceBundleReport?>(null) }
    var evidenceStatus by remember { mutableStateOf<String?>(null) }
    var evidenceBusy by remember { mutableStateOf(false) }

    val exportBundle = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val current = bundle
        if (uri != null && current != null) {
            evidenceBusy = true
            evidenceStatus = "EVIDENCE_BUNDLE_EXPORTING"
            scope.launch {
                EvidenceBundleManager.exportToUri(
                    context = context,
                    bundle = current,
                    destination = uri,
                )
                    .onSuccess {
                        evidenceStatus = "EVIDENCE_BUNDLE_EXPORTED"
                    }
                    .onFailure { error ->
                        evidenceStatus =
                            "EVIDENCE_BUNDLE_EXPORT_FAILED: " +
                                (error.message ?: error.javaClass.simpleName)
                    }
                evidenceBusy = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Sistema", style = MaterialTheme.typography.titleMedium)

        Section("PocketPC") {
            ValueRow("Versão", "0.1.0-alpha15")
            ValueRow("Desktop shell", "IMPLEMENTED")
            ValueRow(
                "Arquivos SAF",
                if (storageConfigured) "IMPLEMENTED / CONFIGURED" else "IMPLEMENTED",
            )
            ValueRow("Local Android shell", "IMPLEMENTED")
            ValueRow("Runtime staging", "IMPLEMENTED")
            ValueRow("Safe rootfs install", "IMPLEMENTED")
            ValueRow("Guest link semantics", "IMPLEMENTED / NOT DEVICE VALIDATED")
            ValueRow("NOFOLLOW cleanup", "IMPLEMENTED")
            ValueRow("Device evidence harness", "IMPLEMENTED / USER-RUN TEST")
            ValueRow("Evidence bundle", "IMPLEMENTED / EXPORTABLE")
            ValueRow("Local Windows builder", "IMPLEMENTED / EXECUTION PENDING")
            ValueRow("Device install gate", "IMPLEMENTED / ADB EXECUTION PENDING")
            ValueRow("Automated physical runner", "DEBUG ONLY / EXECUTION PENDING")
            ValueRow("One-command physical test", "IMPLEMENTED / WINDOWS EXECUTION PENDING")
            ValueRow(
                "Native Runtime Host",
                if (nativeHost.loaded) "IMPLEMENTED / LOADED" else "IMPLEMENTED / LOAD FAILED",
            )
            ValueRow("Execution substrate", substrate.state)
            ValueRow(
                "Approval manifest",
                if (substrate.artifactContractApproved) "APPROVED" else "LOCKED / NOT APPROVED",
            )
            ValueRow(
                "Policy digests",
                if (substrate.policyDigestsVerified) "VERIFIED" else "NOT VERIFIED",
            )
            ValueRow(
                "Artifact hashes",
                if (substrate.artifactIntegrityVerified) "ATTESTED" else "NOT ATTESTED",
            )
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
                "${formatBytes(snapshot.internalFreeBytes)} livres / " +
                    formatBytes(snapshot.internalTotalBytes),
            )
        }

        Section("Teste do dispositivo") {
            Text(
                "Teste local e não destrutivo: usa somente diretórios privados temporários, " +
                    "não executa PRoot e não requer root.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !evidenceBusy,
                    onClick = {
                        evidenceBusy = true
                        evidence = null
                        bundle = null
                        evidenceStatus = "DEVICE_EVIDENCE_RUNNING"

                        scope.launch {
                            DeviceEvidenceCollector.collectAndPersist(
                                context = context,
                                nativeHost = nativeHost,
                                substrate = substrate,
                            )
                                .onSuccess { report ->
                                    evidence = report
                                    evidenceStatus = if (report.filesystem.allCriticalPassed) {
                                        "DEVICE_FILESYSTEM_SELFTEST_PASS"
                                    } else {
                                        "DEVICE_FILESYSTEM_SELFTEST_PARTIAL_OR_FAIL"
                                    }

                                    EvidenceBundleManager.create(context, report)
                                        .onSuccess { created ->
                                            bundle = created
                                            evidenceStatus =
                                                if (report.filesystem.allCriticalPassed) {
                                                    "DEVICE_EVIDENCE_BUNDLE_READY"
                                                } else {
                                                    "DEVICE_EVIDENCE_BUNDLE_READY_WITH_FAILURES"
                                                }
                                        }
                                        .onFailure { error ->
                                            evidenceStatus =
                                                "EVIDENCE_BUNDLE_CREATE_FAILED: " +
                                                    (error.message ?: error.javaClass.simpleName)
                                        }
                                }
                                .onFailure { error ->
                                    evidenceStatus =
                                        "DEVICE_EVIDENCE_FAILED: " +
                                            (error.message ?: error.javaClass.simpleName)
                                }
                            evidenceBusy = false
                        }
                    },
                ) {
                    Text("Executar teste")
                }

                OutlinedButton(
                    enabled = !evidenceBusy && bundle != null,
                    onClick = {
                        val current = bundle ?: return@OutlinedButton
                        val revision = current.buildIdentity.sourceRevision
                            .let { if (current.buildIdentity.sourceRevisionPinned) it.take(12) else "local" }
                        exportBundle.launch(
                            "PocketPC-${current.buildIdentity.versionName}-$revision-evidence.zip"
                        )
                    },
                ) {
                    Text("Exportar bundle")
                }
            }

            if (evidenceBusy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            evidenceStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            evidence?.let { report ->
                ValueRow("Symlink relativo", passLabel(report.filesystem.relativeSymlink.passed))
                ValueRow("Symlink absoluto", passLabel(report.filesystem.absoluteSymlink.passed))
                ValueRow("Hardlink", passLabel(report.filesystem.hardlink.passed))
                ValueRow("NOFOLLOW cleanup", passLabel(report.filesystem.noFollowCleanup.passed))
                ValueRow(
                    "Alvo externo preservado",
                    passLabel(report.filesystem.externalTargetPreserved.passed),
                )
                ValueRow(
                    "Filesystem gate",
                    if (report.filesystem.allCriticalPassed) "PASS" else "FAIL / REVIEW",
                )
                ValueRow("Substrate attested", if (report.prootReady) "YES" else "NO")
                ValueRow(
                    "Source revision",
                    if (report.buildIdentity.sourceRevisionPinned) {
                        report.buildIdentity.sourceRevision.take(12)
                    } else {
                        "LOCAL_UNPINNED"
                    },
                )
                ValueRow(
                    "Assinaturas APK",
                    report.buildIdentity.signingCertificateSha256.size.toString(),
                )
                Text(
                    "Evidence interno: ${report.outputFile.path}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Evidence SHA-256: ${report.outputSha256}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            bundle?.let { report ->
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ValueRow("Bundle entries", report.entryCount.toString())
                ValueRow(
                    "Build pinned",
                    if (report.buildIdentity.sourceRevisionPinned) "YES" else "NO",
                )
                Text(
                    "Bundle SHA-256: ${report.bundleSha256}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Section("Runtime host") {
            Text(nativeHost.probe, style = MaterialTheme.typography.bodySmall)
            Text(
                "nativeLibraryDir: ${nativeHost.nativeLibraryDir}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("substrate: ${substrate.state}", style = MaterialTheme.typography.bodySmall)

            substrate.approvalErrors.forEach { error ->
                Text(
                    "approval: $error",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            substrate.components.forEach { component ->
                Text(
                    "${component.fileName}: exists=${component.exists}, exec=${component.executable}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Section("Vulkan — PackageManager") {
            if (snapshot.vulkanFeatures.isEmpty()) {
                Text(
                    "Nenhuma feature Vulkan foi exposta pelo PackageManager.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                snapshot.vulkanFeatures.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Section("Vulkan — NDK probe") {
            Text(nativeHost.graphicsProbe, style = MaterialTheme.typography.bodySmall)
            Text(
                "Este probe apenas enumera capacidades. " +
                    "Ele não renderiza, não mede FPS e não valida uma vGPU.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun passLabel(value: Boolean): String = if (value) "PASS" else "FAIL"

@Composable
private fun Section(title: String, body: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 6.dp),
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = body)
}

@Composable
internal fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(0.42f))
        Text(value, modifier = Modifier.weight(0.58f))
    }
}
