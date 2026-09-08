package dev.pocketpc.core.runtime

import android.content.Context
import java.io.File

enum class BindAuthority {
    SYSTEM,
    USER,
}

data class RuntimeBindSpec(
    val hostPath: File,
    val guestPath: String,
    val readOnly: Boolean,
    val purpose: String,
    val authority: BindAuthority,
)

data class BindValidation(
    val valid: Boolean,
    val errors: List<String>,
)

object RuntimeBindPolicy {
    private val userReserved = setOf(
        "/",
        "/proc",
        "/sys",
        "/dev",
        "/tmp",
        "/home/pocket",
        "/host-rootfs",
    )

    fun validate(
        binds: List<RuntimeBindSpec>,
        allowedHostRoots: List<File>,
    ): BindValidation {
        val errors = mutableListOf<String>()
        val normalizedRoots = allowedHostRoots.mapNotNull {
            runCatching { it.canonicalFile }.getOrNull()
        }
        val seenGuest = HashSet<String>()

        binds.forEachIndexed { index, bind ->
            val host = runCatching { bind.hostPath.canonicalFile }.getOrNull()
            if (host == null || !host.exists()) {
                errors += "bind[$index] host inexistente/inválido."
            } else {
                val allowed = normalizedRoots.any { root ->
                    host == root || host.path.startsWith(root.path + File.separator)
                }
                if (!allowed) errors += "bind[$index] host fora da allowlist."
                if (hasReservedHostSyntax(host.path)) {
                    errors += "bind[$index] host contém caractere incompatível."
                }
            }

            val guest = normalizeGuestPath(bind.guestPath)
            if (guest == null) {
                errors += "bind[$index] guest inválido."
            } else {
                if (!seenGuest.add(guest)) errors += "bind[$index] guest duplicado: $guest"
                if (bind.authority == BindAuthority.USER && guest in userReserved) {
                    errors += "bind[$index] guest reservado ao sistema: $guest"
                }
                if (':' in guest || '!' in guest) {
                    errors += "bind[$index] guest contém sintaxe reservada do PRoot."
                }
            }

            if (bind.purpose.isBlank() || bind.purpose.length > 80) {
                errors += "bind[$index] purpose inválido."
            }
        }

        return BindValidation(errors.isEmpty(), errors)
    }

    fun normalizeGuestPath(raw: String): String? {
        if (!raw.startsWith("/") || '\u0000' in raw || '\\' in raw || '\n' in raw) return null
        val parts = ArrayList<String>()
        raw.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> return null
                else -> parts += part
            }
        }
        return "/" + parts.joinToString("/")
    }

    private fun hasReservedHostSyntax(path: String): Boolean {
        if ('\n' in path || '\u0000' in path) return true

        val windowsDrivePrefix =
            File.separatorChar == '\\' &&
                path.length >= 3 &&
                path[0].isLetter() &&
                path[1] == ':' &&
                (path[2] == '\\' || path[2] == '/')

        return path.withIndex().any { (index, character) ->
            character == ':' && !(windowsDrivePrefix && index == 1)
        }
    }
}

class RuntimeBindPlanner(private val context: Context) {
    fun homeDirectory(
        runtime: InstalledRuntime,
    ): File =
        File(
            context.filesDir,
            "runtime-home/${runtime.manifest.id}/${runtime.manifest.version}",
        ).apply {
            require(mkdirs() || isDirectory)
        }

    private fun tempDirectory(
        runtime: InstalledRuntime,
    ): File =
        File(
            context.cacheDir,
            "runtime-tmp/${runtime.manifest.id}/${runtime.manifest.version}",
        ).apply {
            require(mkdirs() || isDirectory)
        }

    fun base(runtime: InstalledRuntime): List<RuntimeBindSpec> {
        val home = homeDirectory(runtime)
        val temp = tempDirectory(runtime)

        return listOf(
            RuntimeBindSpec(
                hostPath = home,
                guestPath = "/home/pocket",
                readOnly = false,
                purpose = "runtime home",
                authority = BindAuthority.SYSTEM,
            ),
            RuntimeBindSpec(
                hostPath = temp,
                guestPath = "/tmp",
                readOnly = false,
                purpose = "runtime temp",
                authority = BindAuthority.SYSTEM,
            ),
        )
    }

    fun allowedHostRoots(): List<File> =
        listOf(context.filesDir, context.noBackupFilesDir, context.cacheDir)
}
