package dev.pocketpc.core.terminal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Executes Android's local /system/bin/sh with the normal app UID.
 * This is intentionally not presented as a Linux container, PTY, root shell or privileged terminal.
 */
class LocalShellEngine(context: Context) {
    private val home = context.filesDir.canonicalFile
    private val tmp = File(context.cacheDir, "terminal-tmp").apply { mkdirs() }

    var workingDirectory: File = home
        private set

    suspend fun execute(rawCommand: String): ShellResult = withContext(Dispatchers.IO) {
        val command = rawCommand.trim()
        if (command.isEmpty()) {
            return@withContext ShellResult(command, "", 0, false, workingDirectory.path)
        }

        when (command) {
            "help" -> return@withContext success(command, ShellBuiltins.HELP)
            "pwd" -> return@withContext success(command, workingDirectory.path)
            "exit" -> return@withContext success(command, "Sessão lógica encerrada. Feche a janela para sair do Terminal.")
        }

        ShellBuiltins.tokenizeCd(command)?.let { requested ->
            return@withContext changeDirectory(command, requested)
        }

        runProcess(command)
    }

    private fun changeDirectory(command: String, requested: String): ShellResult {
        val target = when {
            requested.isBlank() || requested == "~" -> home
            requested.startsWith("~/") -> File(home, requested.removePrefix("~/"))
            File(requested).isAbsolute -> File(requested)
            else -> File(workingDirectory, requested)
        }

        val canonical = runCatching { target.canonicalFile }.getOrElse {
            return failure(command, "cd: caminho inválido: $requested", 1)
        }
        if (!canonical.exists()) return failure(command, "cd: não existe: $requested", 1)
        if (!canonical.isDirectory) return failure(command, "cd: não é uma pasta: $requested", 1)

        workingDirectory = canonical
        return success(command, workingDirectory.path)
    }

    private suspend fun runProcess(command: String): ShellResult = coroutineScope {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .apply {
                environment()["HOME"] = home.path
                environment()["TMPDIR"] = tmp.path
                environment()["TERM"] = "xterm-256color"
            }
            .start()

        val outputDeferred = async(Dispatchers.IO) {
            process.inputStream.bufferedReader().use { reader ->
                val builder = StringBuilder()
                val buffer = CharArray(2048)
                while (builder.length < MAX_OUTPUT_CHARS) {
                    val read = reader.read(buffer, 0, minOf(buffer.size, MAX_OUTPUT_CHARS - builder.length))
                    if (read < 0) break
                    builder.append(buffer, 0, read)
                }
                if (builder.length >= MAX_OUTPUT_CHARS) {
                    builder.append("\n[saída truncada pelo Pocket Terminal]")
                }
                builder.toString().trimEnd()
            }
        }

        val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(300, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            runCatching { process.inputStream.close() }
        }

        val output = runCatching { outputDeferred.await() }
            .getOrElse { "Falha ao ler a saída: ${it.message ?: it::class.java.simpleName}" }

        ShellResult(
            command = command,
            output = output.ifBlank { if (finished) "(sem saída)" else "Tempo limite excedido." },
            exitCode = if (finished) runCatching { process.exitValue() }.getOrNull() else null,
            timedOut = !finished,
            workingDirectory = workingDirectory.path,
        )
    }

    private fun success(command: String, output: String) =
        ShellResult(command, output, 0, false, workingDirectory.path)

    private fun failure(command: String, output: String, exitCode: Int) =
        ShellResult(command, output, exitCode, false, workingDirectory.path)

    companion object {
        private const val COMMAND_TIMEOUT_SECONDS = 8L
        private const val MAX_OUTPUT_CHARS = 64 * 1024
    }
}
