package dev.pocketpc.core.terminal

data class ShellResult(
    val command: String,
    val output: String,
    val exitCode: Int?,
    val timedOut: Boolean,
    val workingDirectory: String,
)

data class TerminalRecord(
    val prompt: String,
    val command: String,
    val output: String,
    val exitCode: Int?,
    val timedOut: Boolean,
)
