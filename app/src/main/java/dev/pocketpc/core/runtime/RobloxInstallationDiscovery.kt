package dev.pocketpc.core.runtime

import java.io.File

data class RobloxPlayerInstallation(
    val versionDirectoryName: String,
    val hostExecutable: File,
    val windowsExecutable: String,
    val bytes: Long,
    val modifiedAtMillis: Long,
)

data class RobloxInstallationDiscovery(
    val versionsRoot: File,
    val installations: List<RobloxPlayerInstallation>,
    val selected: RobloxPlayerInstallation?,
    val blockers: List<String>,
) {
    val installed: Boolean
        get() = selected != null
}

object RobloxInstallationDiscoveryProbe {
    private const val PLAYER_EXE =
        "RobloxPlayerBeta.exe"
    private val versionDirectory =
        Regex("^version-[A-Za-z0-9._-]{1,96}$")

    fun discover(
        layout: WindowsPrefixLayout,
    ): RobloxInstallationDiscovery {
        val versionsRoot =
            File(
                layout.pocketUser,
                "AppData/Local/Roblox/Versions",
            )

        val canonicalVersions =
            runCatching {
                versionsRoot.canonicalFile
            }.getOrElse {
                return RobloxInstallationDiscovery(
                    versionsRoot = versionsRoot,
                    installations = emptyList(),
                    selected = null,
                    blockers =
                        listOf(
                            "ROBLOX_VERSIONS_ROOT_INVALID",
                        ),
                )
            }

        val canonicalPocketUser =
            runCatching {
                layout.pocketUser.canonicalFile
            }.getOrElse {
                return RobloxInstallationDiscovery(
                    versionsRoot = versionsRoot,
                    installations = emptyList(),
                    selected = null,
                    blockers =
                        listOf(
                            "ROBLOX_PREFIX_USER_ROOT_INVALID",
                        ),
                )
            }

        if (!isStrictDescendant(canonicalPocketUser, canonicalVersions)) {
            return RobloxInstallationDiscovery(
                versionsRoot = canonicalVersions,
                installations = emptyList(),
                selected = null,
                blockers =
                    listOf(
                        "ROBLOX_VERSIONS_ROOT_ESCAPED_PREFIX",
                    ),
            )
        }

        if (!SafeTreeOps.isPlainDirectory(canonicalVersions.toPath())) {
            return RobloxInstallationDiscovery(
                versionsRoot = canonicalVersions,
                installations = emptyList(),
                selected = null,
                blockers =
                    listOf(
                        "ROBLOX_CLASSIC_PLAYER_NOT_INSTALLED",
                    ),
            )
        }

        val candidates =
            canonicalVersions.listFiles()
                .orEmpty()
                .asSequence()
                .filter {
                    versionDirectory.matches(it.name)
                }
                .mapNotNull { directory ->
                    inspectVersionDirectory(
                        canonicalVersions,
                        directory,
                    )
                }
                .sortedWith(
                    compareByDescending<RobloxPlayerInstallation> {
                        it.modifiedAtMillis
                    }.thenByDescending {
                        it.versionDirectoryName
                    },
                )
                .toList()

        return RobloxInstallationDiscovery(
            versionsRoot = canonicalVersions,
            installations = candidates,
            selected = candidates.firstOrNull(),
            blockers =
                if (candidates.isEmpty()) {
                    listOf(
                        "ROBLOX_CLASSIC_PLAYER_NOT_INSTALLED",
                    )
                } else {
                    emptyList()
                },
        )
    }

    private fun inspectVersionDirectory(
        versionsRoot: File,
        directory: File,
    ): RobloxPlayerInstallation? {
        if (!SafeTreeOps.isPlainDirectory(directory.toPath())) {
            return null
        }

        val canonicalDirectory =
            runCatching {
                directory.canonicalFile
            }.getOrNull()
                ?: return null
        if (canonicalDirectory.parentFile != versionsRoot) {
            return null
        }

        val player =
            File(
                canonicalDirectory,
                PLAYER_EXE,
            )
        if (!SafeTreeOps.isPlainFile(player.toPath())) {
            return null
        }

        val canonicalPlayer =
            runCatching {
                player.canonicalFile
            }.getOrNull()
                ?: return null
        if (canonicalPlayer.parentFile != canonicalDirectory) {
            return null
        }
        if (
            !canonicalPlayer.canRead() ||
            canonicalPlayer.length() <= 0L
        ) {
            return null
        }

        return RobloxPlayerInstallation(
            versionDirectoryName = canonicalDirectory.name,
            hostExecutable = canonicalPlayer,
            windowsExecutable =
                "C:\\users\\pocket\\AppData\\Local\\Roblox\\Versions\\" +
                    canonicalDirectory.name +
                    "\\" +
                    PLAYER_EXE,
            bytes = canonicalPlayer.length(),
            modifiedAtMillis = canonicalPlayer.lastModified(),
        )
    }

    internal fun isStrictDescendant(
        root: File,
        candidate: File,
    ): Boolean {
        val rootPath = root.toPath().normalize()
        val candidatePath = candidate.toPath().normalize()
        return candidatePath != rootPath &&
            candidatePath.startsWith(rootPath)
    }
}
