package dev.pocketpc.core.runtime

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class EvidenceBundleReport(
    val bundleFile: File,
    val bundleSha256: String,
    val sha256File: File,
    val entryCount: Int,
    val buildIdentity: BuildIdentity,
)

object EvidenceBundleManager {
    suspend fun create(
        context: Context,
        evidence: DeviceEvidenceReport,
    ): Result<EvidenceBundleReport> = withContext(Dispatchers.IO) {
        runCatching {
            require(evidence.outputFile.isFile) { "Device evidence JSON ausente." }
            require(evidence.sha256File.isFile) { "Device evidence SHA-256 ausente." }

            val identity = BuildIdentityCollector.collect(context)
            val entries = mutableListOf(
                EvidenceBundleEntry(
                    "evidence/device-evidence.json",
                    evidence.outputFile.readBytes(),
                ),
                EvidenceBundleEntry(
                    "evidence/device-evidence.sha256",
                    evidence.sha256File.readBytes(),
                ),
                EvidenceBundleEntry(
                    "identity/build-identity.json",
                    BuildIdentityCollector.toJson(identity)
                        .toString(2)
                        .toByteArray(Charsets.UTF_8),
                ),
            )

            addAsset(
                context,
                entries,
                "proot-substrate-approval.json",
                "policy/proot-substrate-approval.json",
                required = true,
            )
            addAsset(
                context,
                entries,
                "proot/LOCK.json",
                "policy/proot/LOCK.json",
                required = true,
            )
            addAsset(
                context,
                entries,
                "proot/ARTIFACT_CONTRACT.json",
                "policy/proot/ARTIFACT_CONTRACT.json",
                required = true,
            )
            addAsset(
                context,
                entries,
                "proot/ARTIFACTS.lock.json",
                "policy/proot/ARTIFACTS.lock.json",
                required = false,
            )

            val metadata = JSONObject()
                .put("schemaVersion", 1)
                .put("purpose", "PocketPC physical-device evidence bundle")
                .put("evidenceSha256", evidence.outputSha256)
                .put("sourceRevision", identity.sourceRevision)
                .put("sourceRevisionPinned", identity.sourceRevisionPinned)
                .put("versionName", identity.versionName)
                .put("versionCode", identity.versionCode)
                .put("approvalState", evidence.substrateState)
                .put("prootReady", evidence.prootReady)

            entries += EvidenceBundleEntry(
                "bundle-info.json",
                metadata.toString(2).toByteArray(Charsets.UTF_8),
            )

            val built = EvidenceBundleCore.build(entries)
            val root = File(context.noBackupFilesDir, "device-evidence").apply {
                require(mkdirs() || isDirectory)
            }
            val bundle = File(root, "pocketpc-evidence-bundle-latest.zip")
            val sidecar = File(root, "pocketpc-evidence-bundle-latest.sha256")

            atomicWrite(bundle, built.zipBytes)
            atomicWrite(
                sidecar,
                "${built.zipSha256}  ${bundle.name}\n".toByteArray(Charsets.UTF_8),
            )

            EvidenceBundleReport(
                bundleFile = bundle,
                bundleSha256 = built.zipSha256,
                sha256File = sidecar,
                entryCount = entries.size + 1,
                buildIdentity = identity,
            )
        }
    }

    suspend fun exportToUri(
        context: Context,
        bundle: EvidenceBundleReport,
        destination: Uri,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(destination, "w").use { output ->
                requireNotNull(output) { "Não foi possível abrir destino SAF." }
                bundle.bundleFile.inputStream().buffered().use { input ->
                    input.copyTo(output, 64 * 1024)
                }
            }
            Unit
        }
    }

    private fun addAsset(
        context: Context,
        entries: MutableList<EvidenceBundleEntry>,
        assetPath: String,
        bundlePath: String,
        required: Boolean,
    ) {
        val bytes = runCatching {
            context.assets.open(assetPath).use { it.readBytes() }
        }.getOrNull()

        if (bytes == null) {
            require(!required) { "Asset obrigatório ausente: $assetPath" }
            return
        }
        entries += EvidenceBundleEntry(bundlePath, bytes)
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        temp.writeBytes(bytes)
        require(temp.renameTo(target) || run {
            target.delete()
            temp.renameTo(target)
        }) {
            "Falha ao promover ${target.name}."
        }
    }
}
