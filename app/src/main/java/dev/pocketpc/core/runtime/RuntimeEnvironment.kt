package dev.pocketpc.core.runtime

import java.io.File

object RuntimeEnvironment {
    fun minimal(
        home: String = "/home/pocket",
        term: String = "xterm-256color",
    ): Map<String, String> = linkedMapOf(
        "HOME" to home,
        "USER" to "pocket",
        "LOGNAME" to "pocket",
        "SHELL" to "/bin/sh",
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TMPDIR" to "/tmp",
        "LANG" to "C.UTF-8",
        "LC_ALL" to "C.UTF-8",
        "TERM" to term,
    )

    fun forProot(nativeLibraryDir: String): Map<String, String> =
        LinkedHashMap(minimal()).apply {
            put(
                "PROOT_LOADER",
                File(nativeLibraryDir, "libproot_loader.so").path,
            )
        }

    fun validate(environment: Map<String, String>): List<String> {
        val errors = mutableListOf<String>()
        environment.forEach { (key, value) ->
            if (!key.matches(Regex("^[A-Z_][A-Z0-9_]{0,63}$"))) {
                errors += "Variável de ambiente inválida: $key"
            }
            if ('\u0000' in value || '\n' in value || value.length > 4096) {
                errors += "Valor de ambiente inválido: $key"
            }
        }
        return errors
    }
}
