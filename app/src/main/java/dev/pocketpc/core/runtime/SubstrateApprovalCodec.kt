package dev.pocketpc.core.runtime

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

object SubstrateApprovalCodec {
    private const val APPROVAL_ASSET = "proot-substrate-approval.json"
    private const val SOURCE_LOCK_ASSET = "proot/LOCK.json"
    private const val ARTIFACT_CONTRACT_ASSET = "proot/ARTIFACT_CONTRACT.json"
    private const val ARTIFACT_LOCK_ASSET = "proot/ARTIFACTS.lock.json"

    fun read(context: Context): Result<SubstrateApproval> = runCatching {
        val text = context.assets.open(APPROVAL_ASSET)
            .bufferedReader()
            .use { it.readText() }
        parse(text)
    }

    fun verifyPolicyDigests(
        context: Context,
        approval: SubstrateApproval,
    ): Pair<Boolean, List<String>> {
        if (!approval.approved) return false to emptyList()

        val errors = mutableListOf<String>()

        val sourceActual = digestAsset(context, SOURCE_LOCK_ASSET)
        if (sourceActual == null) {
            errors += "Asset $SOURCE_LOCK_ASSET ausente."
        } else if (sourceActual != approval.sourceLockSha256) {
            errors += "sourceLockSha256 divergiu."
        }

        val contractActual = digestAsset(context, ARTIFACT_CONTRACT_ASSET)
        if (contractActual == null) {
            errors += "Asset $ARTIFACT_CONTRACT_ASSET ausente."
        } else if (contractActual != approval.artifactContractSha256) {
            errors += "artifactContractSha256 divergiu."
        }

        val artifactActual = digestAsset(context, ARTIFACT_LOCK_ASSET)
        if (artifactActual == null) {
            errors += "Asset $ARTIFACT_LOCK_ASSET ausente."
        } else if (artifactActual != approval.artifactLockSha256) {
            errors += "artifactLockSha256 divergiu."
        }

        return errors.isEmpty() to errors
    }

    fun parse(json: String): SubstrateApproval {
        val root = JSONObject(json)
        val artifactsJson = root.getJSONArray("artifacts")
        val artifacts = buildList {
            for (index in 0 until artifactsJson.length()) {
                val item = artifactsJson.getJSONObject(index)
                add(
                    ApprovedSubstrateArtifact(
                        role = item.getString("role"),
                        fileName = item.getString("fileName"),
                        sha256 = item.getString("sha256").lowercase(),
                        bytes = item.getLong("bytes"),
                        executableRequired = item.getBoolean("executableRequired"),
                    )
                )
            }
        }
        val review = root.getJSONObject("review")

        return SubstrateApproval(
            schemaVersion = root.getInt("schemaVersion"),
            status = root.getString("status"),
            approved = root.getBoolean("approved"),
            sourceLockSha256 = root.optString("sourceLockSha256", "").lowercase(),
            artifactContractSha256 = root.optString("artifactContractSha256", "").lowercase(),
            artifactLockSha256 = root.optString("artifactLockSha256", "").lowercase(),
            artifacts = artifacts,
            sourceAudit = review.getBoolean("sourceAudit"),
            elfAudit = review.getBoolean("elfAudit"),
            licenseAudit = review.getBoolean("licenseAudit"),
            deviceAudit = review.getBoolean("deviceAudit"),
        )
    }

    private fun digestAsset(context: Context, name: String): String? =
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open(name).buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
        }.getOrNull()
}
