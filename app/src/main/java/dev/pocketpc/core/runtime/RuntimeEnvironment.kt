package dev.pocketpc.core.runtime

import java.io.File

object RuntimeEnvironment {
    /** Search paths for translated child processes, including Wine's wineserver. */
    fun windowsTools(): Map<String, String> = linkedMapOf(
        "BOX64_PATH" to "/opt/pocketpc/wine/bin",
        "BOX64_LD_LIBRARY_PATH" to
            "/opt/pocketpc/wine/lib:/opt/pocketpc/wine/lib64:" +
                "/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu",
    )

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

    fun forProot(nativeLibraryDir: String, hostTempDirectory: String? = null): Map<String, String> =
        LinkedHashMap(minimal()).apply {
            // libproot.so is an executable, not a JVM-loaded JNI library. The
            // Android linker needs an explicit search path for its packaged
            // libtalloc/libandroid-shmem DT_NEEDED dependencies (no RUNPATH).
            put("LD_LIBRARY_PATH", nativeLibraryDir)
            hostTempDirectory?.let { put("PROOT_TMP_DIR", it) }
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
