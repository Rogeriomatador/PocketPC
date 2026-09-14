package dev.pocketpc.core.performance

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RuntimePerformanceMode {
    AUTOMATIC,
    MANUAL,
}

data class RuntimePerformanceSettings(
    val mode: RuntimePerformanceMode = RuntimePerformanceMode.AUTOMATIC,
    val manualFrameRate: Int = DEFAULT_MANUAL_FRAME_RATE,
    val automaticFrameRate: Int? = null,
) {
    fun normalized(): RuntimePerformanceSettings =
        copy(
            manualFrameRate = normalizeFrameRate(manualFrameRate),
            automaticFrameRate =
                automaticFrameRate?.let(::normalizeFrameRate),
        )

    fun effectiveDxvkFrameRate(): Int? =
        when (mode) {
            RuntimePerformanceMode.AUTOMATIC ->
                automaticFrameRate?.let(::normalizeFrameRate)
            RuntimePerformanceMode.MANUAL ->
                normalizeFrameRate(manualFrameRate)
        }

    companion object {
        const val MIN_FRAME_RATE = 30
        const val MAX_FRAME_RATE = 120
        const val DEFAULT_MANUAL_FRAME_RATE = 60

        val MANUAL_FRAME_RATE_OPTIONS =
            listOf(30, 45, 60, 90, 120)

        fun normalizeFrameRate(value: Int): Int =
            value.coerceIn(MIN_FRAME_RATE, MAX_FRAME_RATE)

        fun automaticFrameRateFor(
            action: GovernorAction?,
        ): Int? =
            when (action) {
                null -> null
                GovernorAction.HOLD -> 120
                GovernorAction.WATCH -> 90
                GovernorAction.REDUCE_LOAD -> 60
                GovernorAction.REDUCE_AGGRESSIVELY -> 45
            }
    }
}

/**
 * Process-local performance controller backed by app-private preferences.
 *
 * Automatic mode is deliberately fail-safe: without a fresh governor decision
 * backed by usable pressure evidence it does not invent a thermal target or
 * force an FPS cap. Manual mode is deterministic and is consumed by the
 * Windows/DXVK launch planner.
 */
object RuntimePerformanceController {
    private const val PREFS = "pocketpc-runtime-performance"
    private const val KEY_MODE = "mode"
    private const val KEY_MANUAL_FPS = "manual-fps"

    private val mutableState =
        MutableStateFlow(RuntimePerformanceSettings())
    val state: StateFlow<RuntimePerformanceSettings> =
        mutableState.asStateFlow()

    @Volatile
    private var initialized = false
    private var appContext: Context? = null

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val application = context.applicationContext
        val prefs =
            application.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE,
            )
        val mode =
            runCatching {
                RuntimePerformanceMode.valueOf(
                    prefs.getString(
                        KEY_MODE,
                        RuntimePerformanceMode.AUTOMATIC.name,
                    ) ?: RuntimePerformanceMode.AUTOMATIC.name,
                )
            }.getOrDefault(RuntimePerformanceMode.AUTOMATIC)
        val manualFps =
            prefs.getInt(
                KEY_MANUAL_FPS,
                RuntimePerformanceSettings.DEFAULT_MANUAL_FRAME_RATE,
            )

        appContext = application
        mutableState.value =
            RuntimePerformanceSettings(
                mode = mode,
                manualFrameRate = manualFps,
            ).normalized()
        initialized = true
    }

    fun current(): RuntimePerformanceSettings =
        mutableState.value.normalized()

    fun setMode(mode: RuntimePerformanceMode) {
        update(
            mutableState.value.copy(mode = mode),
            persist = true,
        )
    }

    fun setManualFrameRate(frameRate: Int) {
        update(
            mutableState.value.copy(
                manualFrameRate = frameRate,
            ),
            persist = true,
        )
    }

    fun updateAutomaticDecision(
        action: GovernorAction?,
    ) {
        update(
            mutableState.value.copy(
                automaticFrameRate =
                    RuntimePerformanceSettings
                        .automaticFrameRateFor(action),
            ),
            persist = false,
        )
    }

    private fun update(
        settings: RuntimePerformanceSettings,
        persist: Boolean,
    ) {
        val normalized = settings.normalized()
        mutableState.value = normalized
        if (!persist) return

        appContext
            ?.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE,
            )
            ?.edit()
            ?.putString(KEY_MODE, normalized.mode.name)
            ?.putInt(
                KEY_MANUAL_FPS,
                normalized.manualFrameRate,
            )
            ?.apply()
    }
}
