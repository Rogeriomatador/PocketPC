package dev.pocketpc.core.feature.terminal

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class ShellResult(
    val command: String,
    val cwd: String,
    val output: String,
    val exitCode: Int?,
    val timedOut: Boolean,
)

class SandboxShell(context: Context) {
    private val home: File = context.filesDir.canonicalFile

    @Volatile
    private var currentDirectory: File = home

    val cwd: String
        get() = currentDirectory.absolutePath

    fun execute(rawCommand: String, timeoutSeconds: Long = 8): ShellResult {
        val command = rawCommand.trim()
        if (command.isEmpty()) {
            return ShellResult(command, cwd, "", 0, false)
        }

        if (command == "cd" || command.startsWith("cd ")) {
            return changeDirectory(command)
        }

        if (command == "help") {
            return ShellResult(
                command = command,
                cwd = cwd,
                output = buildString {
                    appendLine("Pocket Terminal — Android sandbox shell")
                    appendLine("Built-ins: help, cd [path]")
                    appendLine("Examples: pwd, ls -la, id, uname -a, echo hello")
                    append("Interactive/TTY programs are not supported in this alpha.")
                },
                exitCode = 0,
                timedOut = false,
            )
        }

        val process = try {
            ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(currentDirectory)
                .redirectErrorStream(true)
                .start()
        } catch (error: Throwable) {
            return ShellResult(command, cwd, "failed to start: ${error.message}", null, false)
        }

        val output = StringBuilder()
        val readerThread = thread(start = true, isDaemon = true, name = "PocketShell-reader") {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (output.length >= MAX_OUTPUT_CHARS) {
                            output.appendLine()
                            output.append("[output truncated]")
                            break
                        }
                        output.appendLine(line)
                    }
                }
            }
        }

        val finished = runCatching {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        }.getOrDefault(false)

        if (!finished) {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
        }

        readerThread.join(750)

        return ShellResult(
            command = command,
            cwd = cwd,
            output = output.toString().trimEnd().ifEmpty {
                if (finished) "(no output)" else "command timed out"
            },
            exitCode = if (finished) runCatching { process.exitValue() }.getOrNull() else null,
            timedOut = !finished,
        )
    }

    private fun changeDirectory(command: String): ShellResult {
        val rawTarget = command.removePrefix("cd").trim()
        val target = if (rawTarget.isBlank() || rawTarget == "~") {
            home
        } else {
            val cleaned = rawTarget
                .removeSurrounding("\\\"")
                .removeSurrounding("'")
            if (cleaned.startsWith("/")) File(cleaned) else File(currentDirectory, cleaned)
        }

        val canonical = runCatching { target.canonicalFile }.getOrElse {
            return ShellResult(command, cwd, "cd: ${it.message}", 1, false)
        }

        if (!canonical.exists()) {
            return ShellResult(command, cwd, "cd: no such directory: ${canonical.path}", 1, false)
        }
        if (!canonical.isDirectory) {
            return ShellResult(command, cwd, "cd: not a directory: ${canonical.path}", 1, false)
        }

        currentDirectory = canonical
        return ShellResult(command, cwd, cwd, 0, false)
    }

    companion object {
        private const val MAX_OUTPUT_CHARS = 64 * 1024
    }
}
