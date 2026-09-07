package dev.pocketpc.core.research

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

data class ResearchCodec(
    val name: String,
    val mimeType: String,
    val hardwareAccelerated: Boolean?,
    val vendor: Boolean?,
)

enum class PreferredRemoteCodec(
    val mimeType: String?,
    val label: String,
) {
    AV1(MediaFormat.MIMETYPE_VIDEO_AV1, "AV1"),
    HEVC(MediaFormat.MIMETYPE_VIDEO_HEVC, "HEVC/H.265"),
    AVC(MediaFormat.MIMETYPE_VIDEO_AVC, "H.264/AVC"),
    NONE(null, "Nenhum encoder de hardware confirmado"),
}

data class VirtualDisplayProbeResult(
    val created: Boolean,
    val displayId: Int?,
    val detail: String,
)

data class PocketPcResearchReport(
    val virtualDisplay: VirtualDisplayProbeResult,
    val encoders: List<ResearchCodec>,
    val preferredRemoteCodec: PreferredRemoteCodec,
    val wifiDirect: Boolean,
    val wifiAware: Boolean,
    val pcHardwareType: Boolean,
) {
    val remoteDesktopFoundationPromising: Boolean
        get() =
            virtualDisplay.created &&
                preferredRemoteCodec !=
                    PreferredRemoteCodec.NONE &&
                (wifiDirect || wifiAware)
}

object PocketPcResearchProbe {
    fun collect(context: Context): PocketPcResearchReport {
        val appContext = context.applicationContext
        val packageManager = appContext.packageManager
        val encoders = collectVideoEncoders()

        return PocketPcResearchReport(
            virtualDisplay =
                probePrivateVirtualDisplay(
                    appContext
                ),
            encoders = encoders,
            preferredRemoteCodec =
                choosePreferredRemoteCodec(
                    encoders
                ),
            wifiDirect =
                packageManager.hasSystemFeature(
                    PackageManager
                        .FEATURE_WIFI_DIRECT
                ),
            wifiAware =
                packageManager.hasSystemFeature(
                    PackageManager
                        .FEATURE_WIFI_AWARE
                ),
            pcHardwareType =
                packageManager.hasSystemFeature(
                    PackageManager.FEATURE_PC
                ),
        )
    }

    private fun collectVideoEncoders():
        List<ResearchCodec> =
        runCatching {
            MediaCodecList(
                MediaCodecList.ALL_CODECS
            )
                .codecInfos
                .asSequence()
                .filter { info ->
                    info.isEncoder
                }
                .flatMap { info ->
                    info.supportedTypes
                        .asSequence()
                        .filter { type ->
                            type.equals(
                                MediaFormat
                                    .MIMETYPE_VIDEO_AVC,
                                ignoreCase = true,
                            ) ||
                                type.equals(
                                    MediaFormat
                                        .MIMETYPE_VIDEO_HEVC,
                                    ignoreCase = true,
                                ) ||
                                type.equals(
                                    MediaFormat
                                        .MIMETYPE_VIDEO_AV1,
                                    ignoreCase = true,
                                )
                        }
                        .map { type ->
                            ResearchCodec(
                                name = info.name,
                                mimeType =
                                    type.lowercase(),
                                hardwareAccelerated =
                                    if (
                                        Build.VERSION.SDK_INT >=
                                        Build.VERSION_CODES.Q
                                    ) {
                                        info.isHardwareAccelerated
                                    } else {
                                        null
                                    },
                                vendor =
                                    if (
                                        Build.VERSION.SDK_INT >=
                                        Build.VERSION_CODES.Q
                                    ) {
                                        info.isVendor
                                    } else {
                                        null
                                    },
                            )
                        }
                }
                .distinctBy {
                    it.name to it.mimeType
                }
                .sortedWith(
                    compareByDescending<ResearchCodec> {
                        it.hardwareAccelerated == true
                    }.thenBy {
                        it.mimeType
                    }.thenBy {
                        it.name
                    }
                )
                .toList()
        }.getOrDefault(emptyList())

    private fun probePrivateVirtualDisplay(
        context: Context,
    ): VirtualDisplayProbeResult {
        val displayManager =
            context.getSystemService(
                Context.DISPLAY_SERVICE
            ) as DisplayManager

        val reader =
            runCatching {
                ImageReader.newInstance(
                    320,
                    180,
                    PixelFormat.RGBA_8888,
                    2,
                )
            }.getOrElse { error ->
                return VirtualDisplayProbeResult(
                    created = false,
                    displayId = null,
                    detail =
                        "ImageReader falhou: " +
                            (
                                error.message
                                    ?: error.javaClass
                                        .simpleName
                                ),
                )
            }

        var virtualDisplay:
            android.hardware.display.VirtualDisplay? =
            null

        return try {
            virtualDisplay =
                displayManager.createVirtualDisplay(
                    "PocketPC Research",
                    320,
                    180,
                    context.resources
                        .displayMetrics
                        .densityDpi
                        .coerceAtLeast(1),
                    reader.surface,
                    DisplayManager
                        .VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                )

            val display =
                virtualDisplay?.display

            if (virtualDisplay != null &&
                display != null
            ) {
                VirtualDisplayProbeResult(
                    created = true,
                    displayId = display.displayId,
                    detail =
                        "Private own-content VirtualDisplay " +
                            "criado e liberado com sucesso.",
                )
            } else {
                VirtualDisplayProbeResult(
                    created = false,
                    displayId = null,
                    detail =
                        "DisplayManager retornou null.",
                )
            }
        } catch (error: Throwable) {
            VirtualDisplayProbeResult(
                created = false,
                displayId = null,
                detail =
                    error.javaClass.simpleName +
                        ": " +
                        (
                            error.message
                                ?: "sem detalhe"
                            ),
            )
        } finally {
            runCatching {
                virtualDisplay?.release()
            }
            runCatching {
                reader.close()
            }
        }
    }
}

internal fun choosePreferredRemoteCodec(
    codecs: List<ResearchCodec>,
): PreferredRemoteCodec {
    val hardwareTypes =
        codecs
            .filter {
                it.hardwareAccelerated == true
            }
            .map {
                it.mimeType.lowercase()
            }
            .toSet()

    return when {
        MediaFormat.MIMETYPE_VIDEO_AV1
            .lowercase() in hardwareTypes ->
            PreferredRemoteCodec.AV1

        MediaFormat.MIMETYPE_VIDEO_HEVC
            .lowercase() in hardwareTypes ->
            PreferredRemoteCodec.HEVC

        MediaFormat.MIMETYPE_VIDEO_AVC
            .lowercase() in hardwareTypes ->
            PreferredRemoteCodec.AVC

        else ->
            PreferredRemoteCodec.NONE
    }
}
