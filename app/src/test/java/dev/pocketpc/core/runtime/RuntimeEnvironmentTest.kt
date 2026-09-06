package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeEnvironmentTest {
    @Test
    fun minimalEnvironmentIsValid() {
        assertTrue(RuntimeEnvironment.validate(RuntimeEnvironment.minimal()).isEmpty())
    }

    @Test
    fun rejectsNewlineInjection() {
        val errors = RuntimeEnvironment.validate(mapOf("HOME" to "/home/pocket\nEVIL=1"))
        assertFalse(errors.isEmpty())
    }

    @Test
    fun rejectsInvalidVariableName() {
        val errors = RuntimeEnvironment.validate(mapOf("LD-PRELOAD" to "x"))
        assertFalse(errors.isEmpty())
    }
}
