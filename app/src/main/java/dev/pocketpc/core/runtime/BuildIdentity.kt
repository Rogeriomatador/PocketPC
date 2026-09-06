package dev.pocketpc.core.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import dev.pocketpc.core.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class BuildIdentity(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val sourceRevision: String,
    val sourceRevisionPinned: Boolean,
    val debug: Boolean,
    val signingCertificateSha256: List<String>,
    val installerPackage: String?,
)

object BuildIdentityCollector {
    fun collect(context: Context): BuildIdentity {
        val packageManager = context.packageManager
        val packageName = context.packageName
        val packageInfo = if (Build.VERSION.SDK_INT >= 28) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNATURES,
            )
        }

        val signatures: List<Signature> = if (Build.VERSION.SDK_INT >= 28) {
            val signingInfo = packageInfo.signingInfo
            when {
                signingInfo == null -> emptyList()
                signingInfo.hasMultipleSigners() ->
                    signingInfo.apkContentsSigners?.toList().orEmpty()
                else ->
                    signingInfo.signingCertificateHistory?.toList().orEmpty()
            }
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures?.toList().orEmpty()
        }

        val certDigests = signatures
            .map { signature -> sha256(signature.toByteArray()) }
            .distinct()
            .sorted()

        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                packageManager.getInstallSourceInfo(packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstallerPackageName(packageName)
            }
        }.getOrNull()

        return BuildIdentity(
            packageName = packageName,
            versionName = packageInfo.versionName ?: BuildConfig.VERSION_NAME,
            versionCode = if (Build.VERSION.SDK_INT >= 28) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            },
            sourceRevision = BuildConfig.POCKETPC_SOURCE_REVISION,
            sourceRevisionPinned = BuildConfig.POCKETPC_SOURCE_REVISION_PINNED,
            debug = BuildConfig.DEBUG,
            signingCertificateSha256 = certDigests,
            installerPackage = installer,
        )
    }

    fun toJson(identity: BuildIdentity): JSONObject =
        JSONObject()
            .put("schemaVersion", 1)
            .put("packageName", identity.packageName)
            .put("versionName", identity.versionName)
            .put("versionCode", identity.versionCode)
            .put("sourceRevision", identity.sourceRevision)
            .put("sourceRevisionPinned", identity.sourceRevisionPinned)
            .put("debug", identity.debug)
            .put(
                "signingCertificateSha256",
                JSONArray(identity.signingCertificateSha256),
            )
            .put("installerPackage", identity.installerPackage)

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
