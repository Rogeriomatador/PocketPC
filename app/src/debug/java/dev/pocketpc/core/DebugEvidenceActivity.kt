package dev.pocketpc.core

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketpc.core.runtime.DeviceEvidenceCollector
import dev.pocketpc.core.runtime.EvidenceBundleManager
import dev.pocketpc.core.runtime.ExecutionSubstrateProbe
import dev.pocketpc.core.runtime.NativeRuntimeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class DebugEvidenceActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val status = mutableStateOf("AUTOMATION_EVIDENCE_STARTING")
        setContent {
            MaterialTheme {
                val current = remember { status }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                ) {
                    Text("PocketPC — Debug Evidence Runner")
                    Text(current.value)
                }
            }
        }

        scope.launch {
            status.value = "AUTOMATION_EVIDENCE_RUNNING"
            runEvidence()
                .onSuccess {
                    status.value = "AUTOMATION_EVIDENCE_PASS"
                }
                .onFailure { error ->
                    status.value =
                        "AUTOMATION_EVIDENCE_FAIL: " +
                            (error.message ?: error.javaClass.simpleName)
                }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runEvidence(): Result<Unit> = runCatching {
        val context = applicationContext
        val externalRoot = requireNotNull(getExternalFilesDir(null)) {
            "External app-specific storage unavailable."
        }
        val automationRoot = File(externalRoot, "automation-evidence")
        if (automationRoot.exists()) {
            require(automationRoot.deleteRecursively()) {
                "Could not clear previous automation evidence."
            }
        }
        require(automationRoot.mkdirs()) {
            "Could not create automation evidence directory."
        }

        writeAtomic(
            File(automationRoot, "automation-state.json"),
            JSONObject()
                .put("schemaVersion", 2)
                .put("state", "RUNNING")
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("sourceRevision", BuildConfig.POCKETPC_SOURCE_REVISION)
                .toString(2)
                .toByteArray(Charsets.UTF_8),
        )

        val nativeHost = NativeRuntimeHost.status(context)
        val substrate = ExecutionSubstrateProbe.inspect(context)

        val evidence = DeviceEvidenceCollector.collectAndPersist(
            context = context,
            nativeHost = nativeHost,
            substrate = substrate,
        ).getOrThrow()

        val bundle = EvidenceBundleManager.create(
            context = context,
            evidence = evidence,
        ).getOrThrow()

        val exportedBundle = File(
            automationRoot,
            "pocketpc-evidence-bundle.zip",
        )
        copyAtomic(bundle.bundleFile, exportedBundle)

        val exportedBundleSidecar = File(
            automationRoot,
            "pocketpc-evidence-bundle.zip.sha256",
        )
        writeAtomic(
            exportedBundleSidecar,
            (
                bundle.bundleSha256 +
                    "  " +
                    exportedBundle.name +
                    "\n"
                ).toByteArray(Charsets.US_ASCII),
        )

        val exportedEvidence = File(
            automationRoot,
            "device-evidence.json",
        )
        copyAtomic(evidence.outputFile, exportedEvidence)

        val result = JSONObject()
            .put("schemaVersion", 1)
            .put("state", "PASS")
            .put("versionName", bundle.buildIdentity.versionName)
            .put("versionCode", bundle.buildIdentity.versionCode)
            .put("sourceRevision", bundle.buildIdentity.sourceRevision)
            .put(
                "sourceRevisionPinned",
                bundle.buildIdentity.sourceRevisionPinned,
            )
            .put("bundleFile", exportedBundle.name)
            .put("bundleSha256", bundle.bundleSha256)
            .put("evidenceSha256", evidence.outputSha256)
            .put(
                "hostFilesystemCriticalPassed",
                evidence.filesystem.hostCriticalPassed,
            )
            .put(
                "runtimeLinkSemanticsReady",
                evidence.filesystem.runtimeLinkSemanticsReady,
            )
            .put(
                "filesystemCriticalPassed",
                evidence.filesystem.hostCriticalPassed,
            )
            .put(
                "allFilesystemCapabilitiesPassed",
                evidence.filesystem.allCriticalPassed,
            )
            .put(
                "filesystem",
                JSONObject()
                    .put(
                        "relativeSymlink",
                        capabilityJson(evidence.filesystem.relativeSymlink),
                    )
                    .put(
                        "absoluteSymlink",
                        capabilityJson(evidence.filesystem.absoluteSymlink),
                    )
                    .put(
                        "hardlink",
                        capabilityJson(evidence.filesystem.hardlink),
                    )
                    .put(
                        "noFollowCleanup",
                        capabilityJson(evidence.filesystem.noFollowCleanup),
                    )
                    .put(
                        "externalTargetPreserved",
                        capabilityJson(evidence.filesystem.externalTargetPreserved),
                    ),
            )
            .put("nativeHostLoaded", evidence.nativeHostLoaded)
            .put("substrateState", evidence.substrateState)
            .put("prootReady", evidence.prootReady)
            .put("automationDirectory", automationRoot.absolutePath)

        writeAtomic(
            File(automationRoot, "automation-result.json"),
            result.toString(2).toByteArray(Charsets.UTF_8),
        )
    }.recoverCatching { error ->
        val externalRoot = getExternalFilesDir(null)
        if (externalRoot != null) {
            val automationRoot = File(externalRoot, "automation-evidence")
            automationRoot.mkdirs()
            val result = JSONObject()
                .put("schemaVersion", 1)
                .put("state", "FAIL")
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("sourceRevision", BuildConfig.POCKETPC_SOURCE_REVISION)
                .put("errorType", error.javaClass.name)
                .put("errorMessage", error.message ?: "")
            runCatching {
                writeAtomic(
                    File(automationRoot, "automation-result.json"),
                    result.toString(2).toByteArray(Charsets.UTF_8),
                )
            }
        }
        throw error
    }

    private fun capabilityJson(
        capability: dev.pocketpc.core.runtime.CapabilityEvidence,
    ): JSONObject =
        JSONObject()
            .put("passed", capability.passed)
            .put("detail", capability.detail)

    private fun copyAtomic(source: File, target: File) {
        require(source.isFile) { "Source file missing: ${source.name}" }
        val temp = File(
            target.parentFile,
            ".${target.name}.${System.nanoTime()}.tmp",
        )
        source.inputStream().buffered().use { input ->
            temp.outputStream().buffered().use { output ->
                input.copyTo(output, 64 * 1024)
            }
        }
        require(sha256(temp) == sha256(source)) {
            "Copied file SHA-256 mismatch: ${source.name}"
        }
        promote(temp, target)
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val temp = File(
            target.parentFile,
            ".${target.name}.${System.nanoTime()}.tmp",
        )
        temp.writeBytes(bytes)
        promote(temp, target)
    }

    private fun promote(temp: File, target: File) {
        require(temp.renameTo(target) || run {
            target.delete()
            temp.renameTo(target)
        }) {
            "Could not promote ${target.name}."
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
