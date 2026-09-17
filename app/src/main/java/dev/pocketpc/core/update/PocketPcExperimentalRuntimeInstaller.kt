package dev.pocketpc.core.update

import android.content.Context
import android.net.Uri
import dev.pocketpc.core.BuildConfig
import dev.pocketpc.core.runtime.GuestToolInstallManager
import dev.pocketpc.core.runtime.GuestToolPackageManager
import dev.pocketpc.core.runtime.InstalledGuestTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val REQUIRED_V52_CAPABILITY =
    "pocketpc.vulkan.continuous-present.v52"

class PocketPcExperimentalRuntimeInstaller(
    context: Context,
    private val catalog: PocketPcExperimentalRuntimeCatalog,
    private val packages: GuestToolPackageManager,
    private val installer: GuestToolInstallManager,
) {
    private val appContext = context.applicationContext
    private val downloadRoot =
        File(
            appContext.noBackupFilesDir,
            "runtime-update-downloads",
        ).apply {
            require(mkdirs() || isDirectory) {
                "EXPERIMENTAL_RUNTIME_DOWNLOAD_ROOT_FAILED"
            }
        }

    suspend fun installPairedV52(): Result<InstalledGuestTool> =
        withContext(Dispatchers.IO) {
            runCatching {
                val offer =
                    requireNotNull(
                        catalog.fetchPairedV52Offer()
                            .getOrThrow()
                    ) {
                        "EXPERIMENTAL_RUNTIME_OFFER_NOT_AVAILABLE"
                    }
                validateOfferAgainstRunningApk(offer)

                recoverDownloads()
                val transaction =
                    File(
                        downloadRoot,
                        ".tmp-v52-" + System.nanoTime() + ".zip",
                    )
                try {
                    downloadAndVerify(offer, transaction)
                    val staged =
                        packages.stageZip(
                            Uri.fromFile(transaction).toString()
                        ).getOrThrow()
                    try {
                        require(staged.manifest.id == "wine") {
                            "EXPERIMENTAL_RUNTIME_STAGED_ID_MISMATCH"
                        }
                        require(
                            staged.manifest.version ==
                                offer.guestToolVersion
                        ) {
                            "EXPERIMENTAL_RUNTIME_STAGED_VERSION_MISMATCH"
                        }

                        val installed =
                            installer.install(staged.directory)
                                .getOrThrow()
                        require(installed.manifest.id == "wine") {
                            "EXPERIMENTAL_RUNTIME_INSTALLED_ID_MISMATCH"
                        }
                        require(
                            installed.manifest.version ==
                                offer.guestToolVersion
                        ) {
                            "EXPERIMENTAL_RUNTIME_INSTALLED_VERSION_MISMATCH"
                        }
                        installed
                    } finally {
                        packages.remove(staged)
                    }
                } finally {
                    if (transaction.exists()) {
                        transaction.delete()
                    }
                }
            }
        }

    private fun validateOfferAgainstRunningApk(
        offer: PocketPcExperimentalRuntimeOffer,
    ) {
        val revision =
            BuildConfig.POCKETPC_SOURCE_REVISION
                .trim()
                .lowercase()
        require(
            BuildConfig.POCKETPC_SOURCE_REVISION_PINNED &&
                Regex("^[0-9a-f]{40}$").matches(revision)
        ) {
            "EXPERIMENTAL_RUNTIME_APK_REVISION_NOT_PINNED"
        }
        require(
            offer.pocketPcSourceRevision == revision
        ) {
            "EXPERIMENTAL_RUNTIME_INSTALL_REVISION_MISMATCH"
        }
        require(
            offer.capabilities ==
                setOf(REQUIRED_V52_CAPABILITY)
        ) {
            "EXPERIMENTAL_RUNTIME_INSTALL_CAPABILITY_MISMATCH"
        }
        require(
            Regex("^[A-Za-z0-9._+-]{1,128}$")
                .matches(offer.guestToolVersion)
        ) {
            "EXPERIMENTAL_RUNTIME_INSTALL_VERSION_INVALID"
        }
    }

    private fun downloadAndVerify(
        offer: PocketPcExperimentalRuntimeOffer,
        destination: File,
    ) {
        require(offer.experimental) {
            "EXPERIMENTAL_RUNTIME_FLAG_REQUIRED"
        }
        require(offer.kind == "wine") {
            "EXPERIMENTAL_RUNTIME_KIND_REQUIRED"
        }
        require(offer.wineVulkanAbi == 52) {
            "EXPERIMENTAL_RUNTIME_ABI_REQUIRED"
        }
        require(offer.url.startsWith("https://")) {
            "EXPERIMENTAL_RUNTIME_HTTPS_REQUIRED"
        }

        val connection =
            URL(offer.url).openConnection()
                as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty(
            "User-Agent",
            "PocketPC-runtime-update",
        )

        try {
            val status = connection.responseCode
            require(status in 200..299) {
                "EXPERIMENTAL_RUNTIME_HTTP_$status"
            }
            require(
                connection.url.protocol.equals(
                    "https",
                    ignoreCase = true,
                )
            ) {
                "EXPERIMENTAL_RUNTIME_REDIRECT_DOWNGRADE"
            }

            val declaredLength = connection.contentLengthLong
            if (declaredLength >= 0L) {
                require(declaredLength == offer.bytes) {
                    "EXPERIMENTAL_RUNTIME_CONTENT_LENGTH_MISMATCH"
                }
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.buffered().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        require(total <= offer.bytes) {
                            "EXPERIMENTAL_RUNTIME_DOWNLOAD_TOO_LARGE"
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }

            require(total == offer.bytes) {
                "EXPERIMENTAL_RUNTIME_DOWNLOAD_SIZE_MISMATCH"
            }
            val actualSha256 =
                digest.digest().joinToString("") { byte ->
                    "%02x".format(byte.toInt() and 0xff)
                }
            require(
                actualSha256.equals(
                    offer.sha256,
                    ignoreCase = true,
                )
            ) {
                "EXPERIMENTAL_RUNTIME_DOWNLOAD_SHA256_MISMATCH"
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun recoverDownloads() {
        downloadRoot.listFiles()
            .orEmpty()
            .filter {
                it.isFile &&
                    it.name.startsWith(".tmp-v52-")
            }
            .forEach { it.delete() }
    }
}
