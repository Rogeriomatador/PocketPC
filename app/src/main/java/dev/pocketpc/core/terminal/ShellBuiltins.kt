package dev.pocketpc.core.terminal

internal object ShellBuiltins {
    const val HELP = """Comandos internos do Pocket Terminal:
help       mostra esta ajuda
pwd        mostra a pasta atual
cd <path>  muda a pasta do shell
clear      limpa o histórico visual
exit       fecha somente a sessão lógica

Outros comandos são enviados para /system/bin/sh dentro das permissões normais do app.
Não há root e isto ainda não é um runtime Linux/PTY completo."""

    fun tokenizeCd(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed == "cd") return "~"
        if (!trimmed.startsWith("cd ")) return null
        return trimmed.removePrefix("cd ").trim().removeSurrounding("\"").removeSurrounding("'")
    }
}
