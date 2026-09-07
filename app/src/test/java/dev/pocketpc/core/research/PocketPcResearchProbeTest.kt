package dev.pocketpc.core.research

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class PocketPcResearchProbeTest {
    @Test
    fun prefersHardwareAv1OverOtherCodecs() {
        val codecs =
            listOf(
                ResearchCodec(
                    name = "hw-avc",
                    mimeType =
                        MediaFormat.MIMETYPE_VIDEO_AVC,
                    hardwareAccelerated = true,
                    vendor = true,
                    surfaceInput = true,
                ),
                ResearchCodec(
                    name = "hw-hevc",
                    mimeType =
                        MediaFormat.MIMETYPE_VIDEO_HEVC,
                    hardwareAccelerated = true,
                    vendor = true,
                    surfaceInput = true,
                ),
                ResearchCodec(
                    name = "hw-av1",
                    mimeType =
                        MediaFormat.MIMETYPE_VIDEO_AV1,
                    hardwareAccelerated = true,
                    vendor = true,
                    surfaceInput = true,
                ),
            )

        assertEquals(
            PreferredRemoteCodec.AV1,
            choosePreferredRemoteCodec(codecs),
        )
    }

    @Test
    fun ignoresSoftwareOnlyCodecForRemotePreference() {
        val codecs =
            listOf(
                ResearchCodec(
                    name = "sw-av1",
                    mimeType =
                        MediaFormat.MIMETYPE_VIDEO_AV1,
                    hardwareAccelerated = false,
                    vendor = false,
                    surfaceInput = true,
                ),
                ResearchCodec(
                    name = "hw-avc",
                    mimeType =
                        MediaFormat.MIMETYPE_VIDEO_AVC,
                    hardwareAccelerated = true,
                    vendor = true,
                    surfaceInput = true,
                ),
            )

        assertEquals(
            PreferredRemoteCodec.AVC,
            choosePreferredRemoteCodec(codecs),
        )
    }

    @Test
    fun reportsNoneWithoutConfirmedHardwareEncoder() {
        assertEquals(
            PreferredRemoteCodec.NONE,
            choosePreferredRemoteCodec(
                emptyList()
            ),
        )
    }
}
