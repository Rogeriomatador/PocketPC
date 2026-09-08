package dev.pocketpc.core.runtime

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
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

    @Test
    fun oneShotReceivesEndOfInput() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())
        val result = RuntimeProcessSupervisor().runOneShot(ProcessRunSpec(
            argv = listOf("/bin/sh", "-c", "read value || printf eof"),
            environment = emptyMap(), timeoutMillis = 1_000,
        ))
        assertEquals(0, result.exitCode)
        assertEquals("eof", result.output)
        assertTrue(!result.timedOut)
    }

    @Test
    fun simultaneousRequestsStartOnlyOneProcess() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())
        val supervisor = RuntimeProcessSupervisor()
        val start = CompletableDeferred<Unit>()
        val attempts = (1..8).map {
            async {
                start.await()
                supervisor.runOneShot(ProcessRunSpec(
                    argv = listOf("/bin/sh", "-c", "sleep 1; printf done"),
                    environment = emptyMap(), timeoutMillis = 5_000,
                ))
            }
        }
        start.complete(Unit)
        val results = attempts.awaitAll()
        assertEquals(1, results.count { it.started })
        assertEquals(7, results.count { !it.started && it.error != null })
        assertEquals("done", results.single { it.started }.output)
    }

    @Test
    fun failedStartDoesNotPreventNextProbe() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())
        val supervisor = RuntimeProcessSupervisor()
        val failed = supervisor.runOneShot(ProcessRunSpec(
            argv = listOf("/pocketpc-nonexistent-executable"), environment = emptyMap(),
        ))
        assertTrue(!failed.started)
        val next = supervisor.runOneShot(ProcessRunSpec(
            argv = listOf("/bin/sh", "-c", "printf recovered"), environment = emptyMap(),
        ))
        assertEquals(0, next.exitCode)
        assertEquals("recovered", next.output)
    }
}
