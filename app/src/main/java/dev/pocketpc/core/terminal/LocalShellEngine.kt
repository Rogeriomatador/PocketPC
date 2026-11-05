package dev.pocketpc.core.terminal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import dev.pocketpc.core.runtime.RuntimeProcessSupervisor
import dev.pocketpc.core.runtime.ProcessRunSpec

/**
 * Executes Android's local /system/bin/sh with the normal app UID.
 * This is intentionally not presented as a Linux container, PTY, root shell or privileged terminal.
 */
class LocalShellEngine(context: Context) {
    private val home = context.filesDir.canonicalFile
    private val tmp = File(context.cacheDir, "terminal-tmp").apply { mkdirs() }

    private val supervisor = RuntimeProcessSupervisor()

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

    private suspend fun runProcess(command: String): ShellResult {
        val environment = System.getenv().toMutableMap().apply {
            put("HOME", home.path)
            put("TMPDIR", tmp.path)
            put("TERM", "xterm-256color")
        }
        val result = supervisor.runOneShot(ProcessRunSpec(
            argv = listOf("/system/bin/sh", "-c", command),
            environment = environment,
            workingDirectory = workingDirectory,
            timeoutMillis = COMMAND_TIMEOUT_SECONDS * 1_000,
            maxOutputBytes = MAX_OUTPUT_CHARS,
        ))
        val output = buildString {
            append(result.output.trimEnd())
            if (result.outputTruncated) append("\n[saída truncada pelo Pocket Terminal]")
            result.error?.let { append("\nFalha ao executar: $it") }
        }.trim()
        return ShellResult(
            command = command,
            output = output.ifBlank { if (result.timedOut) "Tempo limite excedido." else "(sem saída)" },
            exitCode = result.exitCode,
            timedOut = result.timedOut,
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
