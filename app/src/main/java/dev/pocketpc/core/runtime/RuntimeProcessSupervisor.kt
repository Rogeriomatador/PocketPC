package dev.pocketpc.core.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class ProcessRunSpec(
    val argv: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: File? = null,
    val timeoutMillis: Long = 15_000L,
    val maxOutputBytes: Int = 1024 * 1024,
)

data class ProcessRunResult(
    val started: Boolean,
    val exitCode: Int?,
    val timedOut: Boolean,
    val output: String,
    val outputTruncated: Boolean,
    val error: String? = null,
)

class RuntimeProcessSupervisor {
    @Volatile
    private var active: Process? = null

    suspend fun runOneShot(spec: ProcessRunSpec): ProcessRunResult =
        withContext(Dispatchers.IO) {
            if (spec.argv.isEmpty()) {
                return@withContext ProcessRunResult(
                    started = false,
                    exitCode = null,
                    timedOut = false,
                    output = "",
                    outputTruncated = false,
                    error = "argv vazio.",
                )
            }

            require(spec.timeoutMillis in 1_000L..300_000L)
            require(spec.maxOutputBytes in 1024..(16 * 1024 * 1024))

            val process = runCatching {
                // Reserve and publish under the same lock: two callers must never
                // both start a process while active is still null.
                synchronized(this@RuntimeProcessSupervisor) {
                    check(active == null) { "Já existe um processo supervisionado ativo." }
                    ProcessBuilder(spec.argv)
                        .directory(spec.workingDirectory)
                        .redirectErrorStream(true)
                        .apply {
                            environment().clear()
                            environment().putAll(spec.environment)
                        }
                        .start()
                        .also { active = it }
                }
            }.getOrElse {
                return@withContext ProcessRunResult(
                    started = false,
                    exitCode = null,
                    timedOut = false,
                    output = "",
                    outputTruncated = false,
                    error = it.message ?: it.javaClass.simpleName,
                )
            }

            try {
                // One-shot probes have no interactive input; signal EOF immediately.
                process.outputStream.close()
                coroutineScope {
                    val capturedDeferred = async(Dispatchers.IO) {
                        drainCapped(process.inputStream, spec.maxOutputBytes)
                    }

                    val finished = process.waitFor(spec.timeoutMillis, TimeUnit.MILLISECONDS)
                    if (!finished) {
                        process.destroy()
                        if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                            process.destroyForcibly()
                            process.waitFor(2, TimeUnit.SECONDS)
                        }
                    }

                    val captured = capturedDeferred.await()
                    ProcessRunResult(
                        started = true,
                        exitCode = if (finished) runCatching { process.exitValue() }.getOrNull() else null,
                        timedOut = !finished,
                        output = captured.first,
                        outputTruncated = captured.second,
                    )
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
                runCatching { process.outputStream.close() }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                synchronized(this@RuntimeProcessSupervisor) {
                    if (active === process) active = null
                }
            }
        }

    fun stopActive(): Boolean {
        val process = synchronized(this) { active } ?: return false
        process.destroy()
        if (process.isAlive) process.destroyForcibly()
        return true
    }

    private fun drainCapped(input: InputStream, maxBytes: Int): Pair<String, Boolean> {
        val stored = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(8192)
        var truncated = false

        input.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                val room = maxBytes - stored.size()
                if (room > 0) {
                    stored.write(buffer, 0, minOf(room, read))
                }
                if (read > room) truncated = true
            }
        }

        return stored.toByteArray().toString(Charsets.UTF_8) to truncated
    }
}
