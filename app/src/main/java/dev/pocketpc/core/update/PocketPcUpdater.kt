package dev.pocketpc.core.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import dev.pocketpc.core.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val UPDATE_FEED_URL =
    "https://raw.githubusercontent.com/Rogeriomatador/PocketPC/main/updates/stable.json"

data class PocketPcUpdateManifest(
    val schemaVersion: Int,
    val channel: String,
    val published: Boolean,
    val versionCode: Int,
    val versionName: String,
    val sourceRevision: String,
    val packageName: String,
    val minApi: Int,
    val apkUrl: String,
    val apkSha256: String,
    val notes: String,
)

data class PocketPcUpdateCheck(
    val manifest: PocketPcUpdateManifest,
    val updateAvailable: Boolean,
)

data class PocketPcUpdateDownload(
    val id: Long,
    val status: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val localFile: File,
    val manifest: PocketPcUpdateManifest,
)

enum class PocketPcInstallResult {
    INSTALLER_OPENED,
    NEEDS_UNKNOWN_SOURCE_PERMISSION,
}

class PocketPcUpdater(
    private val context: Context,
) {
    private val appContext = context.applicationContext
    private val prefs =
        appContext.getSharedPreferences(
            "pocketpc-updater",
            Context.MODE_PRIVATE,
        )

    suspend fun checkForUpdate():
        Result<PocketPcUpdateCheck> =
        withContext(Dispatchers.IO) {
            runCatching {
                val manifest =
                    fetchManifest(UPDATE_FEED_URL)
                validateManifest(manifest)

                PocketPcUpdateCheck(
                    manifest = manifest,
                    updateAvailable =
                        manifest.published &&
                            manifest.versionCode >
                                BuildConfig.VERSION_CODE,
                )
            }
        }

    fun lastKnownManifest():
        PocketPcUpdateManifest? =
        prefs.getString(KEY_LAST_MANIFEST, null)
            ?.let(::parseManifestSafely)

    fun rememberManifest(
        manifest: PocketPcUpdateManifest,
    ) {
        prefs.edit()
            .putString(
                KEY_LAST_MANIFEST,
                encodeManifest(manifest),
            )
            .apply()
    }

    fun pendingDownloadId(): Long? =
        prefs.getLong(KEY_DOWNLOAD_ID, -1L)
            .takeIf { it >= 0L }

    fun clearPendingDownload() {
        prefs.edit()
            .remove(KEY_DOWNLOAD_ID)
            .remove(KEY_DOWNLOAD_MANIFEST)
            .apply()
    }

    fun beginDownload(
        manifest: PocketPcUpdateManifest,
    ): Result<Long> =
        runCatching {
            require(
                manifest.versionCode >
                    BuildConfig.VERSION_CODE
            ) {
                "A versão publicada não é mais nova."
            }
            require(manifest.apkUrl.startsWith("https://")) {
                "A atualização precisa usar HTTPS."
            }

            val updateDir =
                File(
                    appContext.getExternalFilesDir(
                        Environment.DIRECTORY_DOWNLOADS
                    ),
                    "updates",
                ).apply { mkdirs() }

            val file =
                File(
                    updateDir,
                    "PocketPC-" +
                        manifest.versionName +
                        ".apk",
                )
            if (file.exists()) {
                file.delete()
            }

            val request =
                DownloadManager.Request(
                    Uri.parse(manifest.apkUrl)
                )
                    .setTitle(
                        "PocketPC " +
                            manifest.versionName
                    )
                    .setDescription(
                        "Atualização do PocketPC"
                    )
                    .setMimeType(
                        "application/vnd.android.package-archive"
                    )
                    .setNotificationVisibility(
                        DownloadManager.Request
                            .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true)
                    .setDestinationInExternalFilesDir(
                        appContext,
                        Environment.DIRECTORY_DOWNLOADS,
                        "updates/" + file.name,
                    )

            val manager =
                appContext.getSystemService(
                    Context.DOWNLOAD_SERVICE
                ) as DownloadManager
            val id = manager.enqueue(request)

            prefs.edit()
                .putLong(KEY_DOWNLOAD_ID, id)
                .putString(
                    KEY_DOWNLOAD_MANIFEST,
                    encodeManifest(manifest),
                )
                .apply()

            id
        }

    fun queryPendingDownload():
        PocketPcUpdateDownload? {
        val id =
            pendingDownloadId()
                ?: return null
        val manifest =
            prefs.getString(
                KEY_DOWNLOAD_MANIFEST,
                null,
            )
                ?.let(::parseManifestSafely)
                ?: return null

        val manager =
            appContext.getSystemService(
                Context.DOWNLOAD_SERVICE
            ) as DownloadManager
        val cursor =
            manager.query(
                DownloadManager.Query()
                    .setFilterById(id)
            )
                ?: return null

        cursor.use {
            if (!it.moveToFirst()) {
                return null
            }

            val status =
                it.getInt(
                    it.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_STATUS
                    )
                )
            val downloaded =
                it.getLong(
                    it.getColumnIndexOrThrow(
                        DownloadManager
                            .COLUMN_BYTES_DOWNLOADED_SO_FAR
                    )
                )
            val total =
                it.getLong(
                    it.getColumnIndexOrThrow(
                        DownloadManager
                            .COLUMN_TOTAL_SIZE_BYTES
                    )
                )

            val file =
                File(
                    File(
                        appContext.getExternalFilesDir(
                            Environment.DIRECTORY_DOWNLOADS
                        ),
                        "updates",
                    ),
                    "PocketPC-" +
                        manifest.versionName +
                        ".apk",
                )

            return PocketPcUpdateDownload(
                id = id,
                status = status,
                bytesDownloaded = downloaded,
                totalBytes = total,
                localFile = file,
                manifest = manifest,
            )
        }
    }

    suspend fun verifyPendingDownload():
        Result<PocketPcUpdateDownload> =
        withContext(Dispatchers.IO) {
            runCatching {
                val pending =
                    requireNotNull(
                        queryPendingDownload()
                    ) {
                        "Nenhuma atualização pendente."
                    }

                require(
                    pending.status ==
                        DownloadManager
                            .STATUS_SUCCESSFUL
                ) {
                    "O download ainda não terminou."
                }
                require(
                    pending.localFile.isFile
                ) {
                    "APK baixado não encontrado."
                }

                val sha256 =
                    sha256(pending.localFile)
                require(
                    sha256.equals(
                        pending.manifest.apkSha256,
                        ignoreCase = true,
                    )
                ) {
                    "SHA-256 do APK não confere."
                }

                val archiveInfo =
                    packageArchiveInfo(
                        pending.localFile
                    )
                        ?: error(
                            "O arquivo não é um APK válido."
                        )

                require(
                    archiveInfo.packageName ==
                        appContext.packageName
                ) {
                    "O APK pertence a outro pacote."
                }

                val archiveVersion =
                    if (
                        Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.P
                    ) {
                        archiveInfo.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        archiveInfo.versionCode.toLong()
                    }
                require(
                    archiveVersion ==
                        pending.manifest.versionCode
                            .toLong()
                ) {
                    "versionCode do APK não corresponde " +
                        "ao manifesto."
                }

                require(
                    archiveVersion >
                        BuildConfig.VERSION_CODE
                ) {
                    "O APK não é mais novo que o instalado."
                }

                require(
                    signaturesCompatible(
                        installedPackageInfo(),
                        archiveInfo,
                    )
                ) {
                    "Assinatura do APK não corresponde " +
                        "ao PocketPC instalado."
                }

                pending
            }
        }

    fun requestInstall(
        verified:
            PocketPcUpdateDownload,
    ): Result<PocketPcInstallResult> =
        runCatching {
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O &&
                !appContext.packageManager
                    .canRequestPackageInstalls()
            ) {
                val intent =
                    Intent(
                        Settings
                            .ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse(
                            "package:" +
                                appContext.packageName
                        ),
                    ).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                    }
                appContext.startActivity(intent)
                return@runCatching
                    PocketPcInstallResult
                        .NEEDS_UNKNOWN_SOURCE_PERMISSION
            }

            val manager =
                appContext.getSystemService(
                    Context.DOWNLOAD_SERVICE
                ) as DownloadManager
            val uri =
                requireNotNull(
                    manager.getUriForDownloadedFile(
                        verified.id
                    )
                ) {
                    "O Android não expôs o APK baixado."
                }

            val install =
                Intent(
                    Intent.ACTION_VIEW,
                ).apply {
                    setDataAndType(
                        uri,
                        "application/vnd.android.package-archive",
                    )
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }

            appContext.startActivity(install)
            PocketPcInstallResult.INSTALLER_OPENED
        }

    private fun fetchManifest(
        address: String,
    ): PocketPcUpdateManifest {
        val connection =
            URL(address).openConnection()
                as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty(
            "User-Agent",
            "PocketPC/" +
                BuildConfig.VERSION_NAME,
        )

        try {
            val status = connection.responseCode
            require(status in 200..299) {
                "Servidor de atualização respondeu $status."
            }

            val bytes =
                connection.inputStream
                    .use { input ->
                        input.readNBytes(
                            MAX_MANIFEST_BYTES
                        )
                    }
            require(
                bytes.size < MAX_MANIFEST_BYTES
            ) {
                "Manifesto de atualização é grande demais."
            }

            return parseManifest(
                bytes.toString(
                    Charsets.UTF_8
                )
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun validateManifest(
        manifest: PocketPcUpdateManifest,
    ) {
        require(manifest.schemaVersion == 1) {
            "Schema de atualização não suportado."
        }
        require(
            manifest.packageName ==
                appContext.packageName
        ) {
            "Feed pertence a outro package."
        }
        require(
            manifest.minApi <=
                Build.VERSION.SDK_INT
        ) {
            "Atualização exige Android API " +
                manifest.minApi +
                " ou superior."
        }
        if (manifest.published) {
            require(
                manifest.apkUrl
                    .startsWith("https://")
            ) {
                "APK publicado precisa usar HTTPS."
            }
            require(
                manifest.apkSha256
                    .matches(
                        Regex("^[0-9a-fA-F]{64}$")
                    )
            ) {
                "SHA-256 publicado é inválido."
            }
        }
    }

    private fun packageArchiveInfo(
        apk: File,
    ): PackageInfo? =
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {
            appContext.packageManager
                .getPackageArchiveInfo(
                    apk.absolutePath,
                    PackageManager.PackageInfoFlags.of(
                        PackageManager
                            .GET_SIGNING_CERTIFICATES
                            .toLong()
                    ),
                )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager
                .getPackageArchiveInfo(
                    apk.absolutePath,
                    PackageManager
                        .GET_SIGNING_CERTIFICATES,
                )
        }

    private fun installedPackageInfo():
        PackageInfo =
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {
            appContext.packageManager
                .getPackageInfo(
                    appContext.packageName,
                    PackageManager.PackageInfoFlags.of(
                        PackageManager
                            .GET_SIGNING_CERTIFICATES
                            .toLong()
                    ),
                )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager
                .getPackageInfo(
                    appContext.packageName,
                    PackageManager
                        .GET_SIGNING_CERTIFICATES,
                )
        }

    private fun signaturesCompatible(
        installed: PackageInfo,
        candidate: PackageInfo,
    ): Boolean {
        val installedDigests =
            signingDigests(installed)
        val candidateDigests =
            signingDigests(candidate)

        return installedDigests.isNotEmpty() &&
            candidateDigests.isNotEmpty() &&
            installedDigests.intersect(
                candidateDigests
            ).isNotEmpty()
    }

    private fun signingDigests(
        info: PackageInfo,
    ): Set<String> {
        val signatures =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.P
            ) {
                val signingInfo =
                    info.signingInfo
                        ?: return emptySet()
                if (
                    signingInfo
                        .hasMultipleSigners()
                ) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo
                        .signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }

        return signatures
            .orEmpty()
            .map { signature ->
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(
                        signature.toByteArray()
                    )
                    .joinToString("") {
                        byte ->
                        "%02x".format(byte)
                    }
            }
            .toSet()
    }

    companion object {
        private const val KEY_LAST_MANIFEST =
            "last-manifest"
        private const val KEY_DOWNLOAD_ID =
            "download-id"
        private const val KEY_DOWNLOAD_MANIFEST =
            "download-manifest"
        private const val MAX_MANIFEST_BYTES =
            64 * 1024
    }
}

internal fun parseManifest(
    raw: String,
): PocketPcUpdateManifest {
    val json = JSONObject(raw)
    return PocketPcUpdateManifest(
        schemaVersion =
            json.getInt("schemaVersion"),
        channel =
            json.optString(
                "channel",
                "stable",
            ),
        published =
            json.optBoolean(
                "published",
                false,
            ),
        versionCode =
            json.getInt("versionCode"),
        versionName =
            json.getString("versionName"),
        sourceRevision =
            json.optString(
                "sourceRevision",
                "",
            ),
        packageName =
            json.getString("packageName"),
        minApi =
            json.optInt("minApi", 26),
        apkUrl =
            json.optString("apkUrl", ""),
        apkSha256 =
            json.optString(
                "apkSha256",
                "",
            ),
        notes =
            json.optString("notes", ""),
    )
}

private fun parseManifestSafely(
    raw: String,
): PocketPcUpdateManifest? =
    runCatching {
        parseManifest(raw)
    }.getOrNull()

private fun encodeManifest(
    manifest: PocketPcUpdateManifest,
): String =
    JSONObject()
        .put(
            "schemaVersion",
            manifest.schemaVersion,
        )
        .put("channel", manifest.channel)
        .put("published", manifest.published)
        .put(
            "versionCode",
            manifest.versionCode,
        )
        .put(
            "versionName",
            manifest.versionName,
        )
        .put(
            "sourceRevision",
            manifest.sourceRevision,
        )
        .put(
            "packageName",
            manifest.packageName,
        )
        .put("minApi", manifest.minApi)
        .put("apkUrl", manifest.apkUrl)
        .put(
            "apkSha256",
            manifest.apkSha256,
        )
        .put("notes", manifest.notes)
        .toString()

private fun sha256(
    file: File,
): String {
    val digest =
        MessageDigest.getInstance("SHA-256")
    file.inputStream()
        .buffered(256 * 1024)
        .use { input ->
            val buffer =
                ByteArray(256 * 1024)
            while (true) {
                val count =
                    input.read(buffer)
                if (count < 0) break
                if (count > 0) {
                    digest.update(
                        buffer,
                        0,
                        count,
                    )
                }
            }
        }

    return digest.digest()
        .joinToString("") { byte ->
            "%02x".format(byte)
        }
}
