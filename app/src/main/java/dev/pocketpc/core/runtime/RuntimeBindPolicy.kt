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

/**
 * Resolves a stable user HOME for one runtime family.
 *
 * Runtime binaries/rootfs remain versioned, but user state must not be. The
 * hidden `.user` directory is intentionally outside VersionedInstallPruner's
 * candidate set, so Wine prefixes, registry, application state and account
 * sessions survive a runtime-version replacement.
 *
 * Older PocketPC builds stored HOME under `<runtimeId>/<runtimeVersion>`. On
 * first use after this migration we move the unambiguous legacy HOME into the
 * stable location. Ambiguous legacy state fails closed instead of guessing.
 */
internal object RuntimeUserHomeLayout {
    private const val RUNTIME_HOME_ROOT = "runtime-home"
    private const val STABLE_USER_HOME = ".user"

    fun resolve(
        filesDir: File,
        runtimeId: String,
        runtimeVersion: String,
    ): File {
        requireSafeSegment(runtimeId, "runtimeId")
        requireSafeSegment(runtimeVersion, "runtimeVersion")

        val filesRoot = filesDir.canonicalFile
        require(filesRoot.isDirectory || filesRoot.mkdirs()) {
            "RUNTIME_USER_HOME_FILES_ROOT_UNAVAILABLE"
        }

        val homeRoot = File(filesRoot, RUNTIME_HOME_ROOT).canonicalFile
        require(homeRoot.parentFile == filesRoot) {
            "RUNTIME_USER_HOME_ROOT_ESCAPED"
        }
        require(homeRoot.isDirectory || homeRoot.mkdirs()) {
            "RUNTIME_USER_HOME_ROOT_CREATE_FAILED"
        }

        val runtimeRoot = File(homeRoot, runtimeId).canonicalFile
        require(runtimeRoot.parentFile == homeRoot) {
            "RUNTIME_USER_HOME_RUNTIME_ESCAPED"
        }
        require(runtimeRoot.isDirectory || runtimeRoot.mkdirs()) {
            "RUNTIME_USER_HOME_RUNTIME_CREATE_FAILED"
        }
        require(SafeTreeOps.isPlainDirectory(runtimeRoot.toPath())) {
            "RUNTIME_USER_HOME_RUNTIME_NOT_PLAIN_DIRECTORY"
        }

        val stableHome = File(runtimeRoot, STABLE_USER_HOME).canonicalFile
        require(stableHome.parentFile == runtimeRoot) {
            "RUNTIME_USER_HOME_STABLE_ESCAPED"
        }
        if (stableHome.exists()) {
            require(SafeTreeOps.isPlainDirectory(stableHome.toPath())) {
                "RUNTIME_USER_HOME_STABLE_NOT_PLAIN_DIRECTORY"
            }
            return stableHome
        }

        val legacyCandidates =
            runtimeRoot.listFiles()
                .orEmpty()
                .filter { candidate ->
                    !candidate.name.startsWith(".") &&
                        SafeTreeOps.isPlainDirectory(candidate.toPath())
                }
                .map { it.canonicalFile }
                .filter { it.parentFile == runtimeRoot }

        val legacyHome =
            legacyCandidates.firstOrNull {
                it.name == runtimeVersion
            } ?: when (legacyCandidates.size) {
                0 -> null
                1 -> legacyCandidates.single()
                else -> error(
                    "RUNTIME_USER_HOME_MIGRATION_AMBIGUOUS:" +
                        legacyCandidates
                            .map(File::getName)
                            .sorted()
                            .joinToString(",")
                )
            }

        if (legacyHome != null) {
            require(legacyHome.renameTo(stableHome)) {
                "RUNTIME_USER_HOME_MIGRATION_FAILED"
            }
        } else {
            require(stableHome.mkdirs() || stableHome.isDirectory) {
                "RUNTIME_USER_HOME_CREATE_FAILED"
            }
        }

        require(SafeTreeOps.isPlainDirectory(stableHome.toPath())) {
            "RUNTIME_USER_HOME_STABLE_NOT_PLAIN_DIRECTORY"
        }
        return stableHome
    }

    private fun requireSafeSegment(
        value: String,
        label: String,
    ) {
        require(
            value.isNotBlank() &&
                value != "." &&
                value != ".." &&
                !value.startsWith(".") &&
                '/' !in value &&
                '\\' !in value &&
                '\u0000' !in value,
        ) {
            "RUNTIME_USER_HOME_UNSAFE_SEGMENT:$label"
        }
    }
}

class RuntimeBindPlanner(private val context: Context) {
    fun homeDirectory(
        runtime: InstalledRuntime,
    ): File =
        RuntimeUserHomeLayout.resolve(
            filesDir = context.filesDir,
            runtimeId = runtime.manifest.id,
            runtimeVersion = runtime.manifest.version,
        )

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
