package dev.pocketpc.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketPcUpdaterPolicyTest {
    @Test
    fun firstAutomaticCheckRunsImmediately() {
        assertTrue(
            shouldRunUpdateCheck(
                enabled = true,
                lastCheckMillis = 0L,
                nowMillis = 1_000L,
                intervalMillis = 6_000L,
            )
        )
    }

    @Test
    fun disabledAutomaticCheckNeverRuns() {
        assertFalse(
            shouldRunUpdateCheck(
                enabled = false,
                lastCheckMillis = 0L,
                nowMillis = Long.MAX_VALUE,
                intervalMillis = 1L,
            )
        )
    }

    @Test
    fun automaticCheckIsThrottledInsideInterval() {
        assertFalse(
            shouldRunUpdateCheck(
                enabled = true,
                lastCheckMillis = 10_000L,
                nowMillis = 15_999L,
                intervalMillis = 6_000L,
            )
        )
    }

    @Test
    fun automaticCheckRunsAtIntervalBoundary() {
        assertTrue(
            shouldRunUpdateCheck(
                enabled = true,
                lastCheckMillis = 10_000L,
                nowMillis = 16_000L,
                intervalMillis = 6_000L,
            )
        )
    }

    @Test
    fun clockRollbackDoesNotBlockUpdatesForever() {
        assertTrue(
            shouldRunUpdateCheck(
                enabled = true,
                lastCheckMillis = 20_000L,
                nowMillis = 10_000L,
                intervalMillis = 6_000L,
            )
        )
    }
}
