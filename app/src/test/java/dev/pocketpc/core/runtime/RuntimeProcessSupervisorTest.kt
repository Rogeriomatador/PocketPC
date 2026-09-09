package dev.pocketpc.core.runtime

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    @Test
    fun inheritedOutputPipeDoesNotDelayCompletedParent() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())
        val started = System.nanoTime()
        val result = RuntimeProcessSupervisor().runOneShot(ProcessRunSpec(
            argv = listOf("/bin/sh", "-c", "sleep 3 & printf parent-done"),
            environment = emptyMap(), timeoutMillis = 1_000,
        ))
        assertEquals(0, result.exitCode)
        assertEquals("parent-done", result.output)
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
    }

    @Test
    fun largeOutputIsCappedWithoutBlockingProducer() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())
        val result = RuntimeProcessSupervisor().runOneShot(ProcessRunSpec(
            argv = listOf("/bin/sh", "-c", "i=0; while [ \"${'$'}i\" -lt 5000 ]; do printf abcdefgh; i=${'$'}((i+1)); done"),
            environment = emptyMap(), maxOutputBytes = 1024,
        ))
        assertEquals(0, result.exitCode)
        assertEquals(1024, result.output.length)
        assertTrue(result.outputTruncated)
    }

    @Test
    fun cancellingProbeReleasesSupervisor() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())
        val supervisor = RuntimeProcessSupervisor()
        val job = launch { supervisor.runOneShot(ProcessRunSpec(
            argv = listOf("/bin/sh", "-c", "exec sleep 10"),
            environment = emptyMap(), timeoutMillis = 15_000,
        )) }
        delay(100)
        job.cancelAndJoin()
        val next = supervisor.runOneShot(ProcessRunSpec(
            argv = listOf("/bin/sh", "-c", "printf resumed"), environment = emptyMap(),
        ))
        assertEquals(0, next.exitCode)
        assertEquals("resumed", next.output)
    }
    @Test
    fun registeredProcessCanBeObservedAndTerminated() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())
        assumeTrue(File("/bin/sleep").canExecute())

        val supervisor =
            RuntimeProcessSupervisor()
        val running =
            async {
                supervisor.runOneShot(
                    ProcessRunSpec(
                        argv =
                            listOf(
                                "/bin/sh",
                                "-c",
                                "exec /bin/sleep 10",
                            ),
                        environment =
                            emptyMap(),
                        timeoutMillis =
                            15_000,
                    ),
                )
            }

        var snapshot:
            RuntimeProcessSnapshot? =
            null
        repeat(100) {
            snapshot =
                RuntimeProcessRegistry
                    .snapshots()
                    .singleOrNull()
            if (snapshot != null) {
                return@repeat
            }
            delay(10)
        }

        val visible =
            requireNotNull(snapshot)
        assertTrue(visible.alive)
        assertTrue(
            visible.argv.contains(
                "/bin/sh",
            ),
        )

        assertTrue(
            RuntimeProcessRegistry
                .terminate(
                    visible.id,
                    force = true,
                ),
        )

        val result = running.await()
        assertTrue(result.started)

        repeat(100) {
            if (
                RuntimeProcessRegistry
                    .snapshots()
                    .isEmpty()
            ) {
                return@repeat
            }
            delay(10)
        }
        assertTrue(
            RuntimeProcessRegistry
                .snapshots()
                .isEmpty(),
        )
    }

}
