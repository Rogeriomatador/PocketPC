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

/**
 * App-level telemetry only. It deliberately does not claim system-wide GPU/CPU counters.
 * Choreographer values describe PocketPC's UI cadence, not third-party game FPS.
 */
data class TelemetrySample(
    val uiFps: Int = 0,
    val averageFrameMs: Double = 0.0,
    val worstFrameMs: Double = 0.0,
    val processCpuPercent: Int = 0,
    val processRamMb: Int = 0,
    val availableRamMb: Int = 0,
    val totalRamMb: Int = 0,
    val lowMemory: Boolean = false,
    val thermalHeadroom: Float? = null,
    val thermalStatus: Int? = null,
)

class FrameCounter {
    private var frames = 0
    private var windowStartNs = 0L
    private var lastFrameNs = 0L
    private var frameTimeTotalMs = 0.0
    private var frameTimeSamples = 0
    private var frameTimeWorstMs = 0.0

    @Volatile var fps: Int = 0
        private set
    @Volatile var averageFrameMs: Double = 0.0
        private set
    @Volatile var worstFrameMs: Double = 0.0
        private set

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (windowStartNs == 0L) windowStartNs = frameTimeNanos
            if (lastFrameNs != 0L) {
                val frameMs = (frameTimeNanos - lastFrameNs) / 1_000_000.0
                frameTimeTotalMs += frameMs
                frameTimeSamples++
                if (frameMs > frameTimeWorstMs) frameTimeWorstMs = frameMs
            }
            lastFrameNs = frameTimeNanos
            frames++

            val elapsed = frameTimeNanos - windowStartNs
            if (elapsed >= 1_000_000_000L) {
                fps = (frames * 1_000_000_000.0 / elapsed).roundToInt()
                averageFrameMs = if (frameTimeSamples > 0) frameTimeTotalMs / frameTimeSamples else 0.0
                worstFrameMs = frameTimeWorstMs

                frames = 0
                frameTimeTotalMs = 0.0
                frameTimeSamples = 0
                frameTimeWorstMs = 0.0
                windowStartNs = frameTimeNanos
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() = Choreographer.getInstance().postFrameCallback(callback)

    fun stop() {
        Choreographer.getInstance().removeFrameCallback(callback)
        frames = 0
        windowStartNs = 0L
        lastFrameNs = 0L
        frameTimeTotalMs = 0.0
        frameTimeSamples = 0
        frameTimeWorstMs = 0.0
    }
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
        val maxProcessPercent = Runtime.getRuntime().availableProcessors().coerceAtLeast(1) * 100
        val cpuPercent = (cpuDeltaMs.toDouble() / wallDeltaMs * 100.0)
            .roundToInt()
            .coerceIn(0, maxProcessPercent)
        lastWallMs = nowWallMs
        lastCpuMs = nowCpuMs

        val debugInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(debugInfo)
        val processRamMb = debugInfo.totalPss / 1024

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val availableRamMb = (memoryInfo.availMem / MIB).toInt()
        val totalRamMb = (memoryInfo.totalMem / MIB).toInt()

        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermalStatus = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else null

        val nowMs = SystemClock.elapsedRealtime()
        if (Build.VERSION.SDK_INT >= 30 && nowMs - lastThermalReadMs >= 10_000L) {
            val value = power.getThermalHeadroom(THERMAL_FORECAST_SECONDS)
            thermalHeadroom = value.takeUnless { it.isNaN() }
            lastThermalReadMs = nowMs
        }

        _sample.value = TelemetrySample(
            uiFps = frameCounter.fps,
            averageFrameMs = frameCounter.averageFrameMs,
            worstFrameMs = frameCounter.worstFrameMs,
            processCpuPercent = cpuPercent,
            processRamMb = processRamMb,
            availableRamMb = availableRamMb,
            totalRamMb = totalRamMb,
            lowMemory = memoryInfo.lowMemory,
            thermalHeadroom = thermalHeadroom,
            thermalStatus = thermalStatus,
        )
    }

    companion object {
        private const val MIB = 1024L * 1024L
        private const val THERMAL_FORECAST_SECONDS = 10
    }
}

fun thermalStatusLabel(status: Int?): String = when (status) {
    null -> "indisponível"
    PowerManager.THERMAL_STATUS_NONE -> "NONE"
    PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
    PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
    PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
    PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
    PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
    PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
    else -> "UNKNOWN($status)"
}

fun thermalHeadroomHint(headroom: Float?): String = when {
    headroom == null -> "sem leitura confiável"
    headroom > 1.0f -> "risco severo: reduzir carga"
    headroom >= 0.95f -> "alto: reduzir carga"
    headroom >= 0.85f -> "atenção térmica"
    else -> "margem aceitável"
}
