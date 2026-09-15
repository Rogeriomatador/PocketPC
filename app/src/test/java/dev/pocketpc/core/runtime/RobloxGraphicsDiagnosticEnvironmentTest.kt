package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RobloxGraphicsDiagnosticEnvironmentTest {
    @Test
    fun controlledAttemptAddsOnlyPocketPcGraphicsSwitches() {
        val base = mapOf("WINEPREFIX" to "/prefix")

        val result = RobloxGraphicsDiagnosticEnvironment.applyTo(base)

        assertEquals("/prefix", result["WINEPREFIX"])
        assertEquals(
            "1",
            result[RobloxGraphicsDiagnosticEnvironment.GRAPHICS_SESSION_PROTOCOL],
        )
        assertEquals(
            "1",
            result[RobloxGraphicsDiagnosticEnvironment.PRESENT_CONTEXT_DIAGNOSTIC],
        )
        assertEquals(
            "1",
            result[RobloxGraphicsDiagnosticEnvironment.PRESENT_COPY_DIAGNOSTIC],
        )
        assertFalse(base.containsKey(RobloxGraphicsDiagnosticEnvironment.PRESENT_COPY_DIAGNOSTIC))
        assertTrue(result.size == base.size + 3)
    }
}
