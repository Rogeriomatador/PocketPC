package dev.pocketpc.core.performance

import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceGovernorTest {
    @Test
    fun unavailableThermalDataDoesNotInventThrottling() {
        val result = PerformanceGovernor.decide(GovernorInput(null, lowMemory = false))
        assertEquals(GovernorAction.HOLD, result.action)
    }

    @Test
    fun lowMemoryRequestsLoadReduction() {
        val result = PerformanceGovernor.decide(GovernorInput(0.20f, lowMemory = true))
        assertEquals(GovernorAction.REDUCE_LOAD, result.action)
    }

    @Test
    fun severeThermalHeadroomRequestsAggressiveReduction() {
        val result = PerformanceGovernor.decide(GovernorInput(1.01f, lowMemory = false))
        assertEquals(GovernorAction.REDUCE_AGGRESSIVELY, result.action)
    }

    @Test
    fun watchBandStartsAtPointEightFive() {
        val result = PerformanceGovernor.decide(GovernorInput(0.85f, lowMemory = false))
        assertEquals(GovernorAction.WATCH, result.action)
    }
}
