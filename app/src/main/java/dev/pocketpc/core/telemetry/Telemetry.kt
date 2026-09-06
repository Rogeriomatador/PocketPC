package dev.pocketpc.core.telemetry

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.view.Choreographer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** App-level telemetry only. It deliberately does not claim system-wide GPU/CPU counters. */
data class TelemetrySample(
    val fps: Int = 0,
    val processCpuPercent: Int = 0,
    val processRamMb: Int = 0,
    val availableRamMb: Int = 0,
    val thermalHeadroom: Float? = null,
    val thermalStatus: Int? = null,
)

class FrameCounter {
    private var frames = 0
    private var windowStartNs = 0L
    @Volatile var fps: Int = 0
        private set

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (windowStartNs == 0L) windowStartNs = frameTimeNanos
            frames++
            val elapsed = frameTimeNanos - windowStartNs
            if (elapsed >= 1_000_000_000L) {
                fps = (frames * 1_000_000_000.0 / elapsed).roundToInt()
                frames = 0
                windowStartNs = frameTimeNanos
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() = Choreographer.getInstance().postFrameCallback(callback)
    fun stop() = Choreographer.getInstance().removeFrameCallback(callback)
}

class TelemetryMonitor(private val context: Context) {
    private val _sample = MutableStateFlow(TelemetrySample())
    val sample: StateFlow<TelemetrySample> = _sample.asStateFlow()

    private val frameCounter = FrameCounter()
    private var job: Job? = null
    private var lastCpuMs = android.os.Process.getElapsedCpuTime()
    private var lastWallMs = SystemClock.elapsedRealtime()
    private var lastThermalReadMs = 0L
    private var thermalHeadroom: Float? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        frameCounter.start()
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                sampleOnce()
                delay(1_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        frameCounter.stop()
    }

    private fun sampleOnce() {
        val nowWallMs = SystemClock.elapsedRealtime()
        val nowCpuMs = android.os.Process.getElapsedCpuTime()
        val wallDeltaMs = (nowWallMs - lastWallMs).coerceAtLeast(1L)
        val cpuDeltaMs = (nowCpuMs - lastCpuMs).coerceAtLeast(0L)
        val cpuPercent = (cpuDeltaMs.toDouble() / wallDeltaMs * 100.0).roundToInt().coerceIn(0, 100)
        lastWallMs = nowWallMs
        lastCpuMs = nowCpuMs

        val debugInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(debugInfo)
        val processRamMb = debugInfo.totalPss / 1024

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val availableRamMb = (memoryInfo.availMem / (1024L * 1024L)).toInt()

        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermalStatus = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else null

        // Read conservatively. Some devices return NaN when queried too frequently.
        val nowMs = SystemClock.elapsedRealtime()
        if (Build.VERSION.SDK_INT >= 30 && nowMs - lastThermalReadMs >= 10_000L) {
            val value = power.getThermalHeadroom(0)
            thermalHeadroom = value.takeUnless { it.isNaN() }
            lastThermalReadMs = nowMs
        }

        _sample.value = TelemetrySample(
            fps = frameCounter.fps,
            processCpuPercent = cpuPercent,
            processRamMb = processRamMb,
            availableRamMb = availableRamMb,
            thermalHeadroom = thermalHeadroom,
            thermalStatus = thermalStatus,
        )
    }
}
