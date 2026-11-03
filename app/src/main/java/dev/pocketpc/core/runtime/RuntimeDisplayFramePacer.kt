package dev.pocketpc.core.runtime

/**
 * Backpressure for the legacy software BGRA framebuffer path.
 *
 * The host already holds one pending FRAME_READY until FRAME_PRESENTED is
 * acknowledged. Waiting *before* that acknowledgment reduces unnecessary
 * framebuffer copies and UI updates without dropping control/keyboard packets.
 * This does not govern experimental Vulkan/WSI frames or claim physical FPS.
 */
internal class RuntimeDisplayFramePacer {
    private var lastAcknowledgedNanos: Long? = null

    fun recommendedDelayMillis(
        nowNanos: Long,
        requestedFramesPerSecond: Int?,
        frameWidth: Int = 0,
        frameHeight: Int = 0,
    ): Long {
        val last = lastAcknowledgedNanos ?: return 0L
        if (nowNanos < last) return 0L
        // Software BGRA copies can be more expensive than a GPU swapchain.
        // Manual 90/120 FPS applies to graphics launch settings, but does not
        // force software framebuffer copies above 60 FPS on the Android UI.
        val configured = (requestedFramesPerSecond ?: DEFAULT_SOFTWARE_FPS)
            .coerceIn(MIN_SOFTWARE_FPS, MAX_SOFTWARE_FPS)
        // Each BGRA frame must be copied into Java and then uploaded by
        // Compose. A large window should not be allowed to flood the Android
        // UI with many tens of megabytes per frame.
        val pixelCount = frameWidth.toLong() * frameHeight.toLong()
        val pixelBudgetFps =
            if (frameWidth > 0 && frameHeight > 0) {
                (MAX_SOFTWARE_PIXELS_PER_SECOND / pixelCount)
                    .coerceIn(MIN_PIXEL_BUDGET_FPS.toLong(), MAX_SOFTWARE_FPS.toLong())
                    .toInt()
            } else {
                MAX_SOFTWARE_FPS
            }
        val target = minOf(configured, pixelBudgetFps)
        val intervalNanos =
            (1_000_000_000L + target - 1L) / target
        val remainingNanos = intervalNanos - (nowNanos - last)
        return if (remainingNanos <= 0L) 0L
        else (remainingNanos + 999_999L) / 1_000_000L
    }

    fun recordAcknowledgement(nowNanos: Long) {
        lastAcknowledgedNanos = nowNanos
    }

    companion object {
        const val MIN_SOFTWARE_FPS = 15
        const val MAX_SOFTWARE_FPS = 60
        const val DEFAULT_SOFTWARE_FPS = 60
        const val MIN_PIXEL_BUDGET_FPS = 5
        const val MAX_SOFTWARE_PIXELS_PER_SECOND = 60_000_000L
    }
}
