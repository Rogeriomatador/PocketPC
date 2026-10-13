package dev.pocketpc.core.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimePerformanceSettingsTest {
    @Test
    fun automaticModeDoesNotInventCapWithoutGovernorDecision() {
        val settings = RuntimePerformanceSettings()
        assertEquals(RuntimePerformanceMode.AUTOMATIC, settings.mode)
        assertNull(settings.effectiveDxvkFrameRate())
        assertNull(
            RuntimePerformanceSettings
                .automaticFrameRateFor(null),
        )
    }

    @Test
    fun manualModeUsesBoundedUserFrameRate() {
        assertEquals(
            30,
            RuntimePerformanceSettings(
                mode = RuntimePerformanceMode.MANUAL,
                manualFrameRate = 5,
            ).effectiveDxvkFrameRate(),
        )
        assertEquals(
            120,
            RuntimePerformanceSettings(
                mode = RuntimePerformanceMode.MANUAL,
                manualFrameRate = 999,
            ).effectiveDxvkFrameRate(),
        )
    }

    @Test
    fun automaticGovernorCapIsBounded() {
        assertEquals(
            120,
            RuntimePerformanceSettings(
                automaticFrameRate = 999,
            ).effectiveDxvkFrameRate(),
        )
    }

    @Test
    fun automaticGovernorActionsMapToExpectedCaps() {
        assertEquals(
            120,
            RuntimePerformanceSettings
                .automaticFrameRateFor(GovernorAction.HOLD),
        )
        assertEquals(
            90,
            RuntimePerformanceSettings
                .automaticFrameRateFor(GovernorAction.WATCH),
        )
        assertEquals(
            60,
            RuntimePerformanceSettings
                .automaticFrameRateFor(GovernorAction.REDUCE_LOAD),
        )
        assertEquals(
            45,
            RuntimePerformanceSettings
                .automaticFrameRateFor(
                    GovernorAction.REDUCE_AGGRESSIVELY,
                ),
        )
    }
}
