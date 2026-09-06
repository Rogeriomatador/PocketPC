package dev.pocketpc.core.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RuntimeProcessSupervisorTest {
    @Test
    fun capturesOneShotOutput() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())

        val result = RuntimeProcessSupervisor().runOneShot(
            ProcessRunSpec(
                argv = listOf("/bin/sh", "-c", "printf pocketpc"),
                environment = emptyMap(),
                timeoutMillis = 2_000,
                maxOutputBytes = 4096,
            )
        )

        assertTrue(result.started)
        assertEquals(0, result.exitCode)
        assertEquals("pocketpc", result.output)
    }

    @Test
    fun timesOutLongRunningProcess() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())

        val result = RuntimeProcessSupervisor().runOneShot(
            ProcessRunSpec(
                argv = listOf("/bin/sh", "-c", "sleep 3"),
                environment = emptyMap(),
                timeoutMillis = 1_000,
                maxOutputBytes = 4096,
            )
        )

        assertTrue(result.started)
        assertTrue(result.timedOut)
    }
}
