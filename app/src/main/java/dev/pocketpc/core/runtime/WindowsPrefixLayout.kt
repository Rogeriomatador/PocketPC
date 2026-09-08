package dev.pocketpc.core.runtime

import java.io.File

data class WindowsPrefixLayout(
    val profileId: String,
    val prefixRoot: File,
    val driveC: File,
    val usersRoot: File,
    val pocketUser: File,
    val temp: File,
    val dosDevices: File,
    val systemRegistry: File,
    val userRegistry: File,
    val userDefRegistry: File,
)

data class WindowsPrefixPlan(
    val valid: Boolean,
    val layout: WindowsPrefixLayout?,
    val blockers: List<String>,
)

object WindowsPrefixPlanner {
    private val profileIdRegex =
        Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

    fun plan(
        storageRoot: File,
        profileId: String,
    ): WindowsPrefixPlan {
        val blockers = mutableListOf<String>()

        if (!profileIdRegex.matches(profileId)) {
            blockers += "WINDOWS_PREFIX_PROFILE_ID_INVALID"
        }

        val canonicalStorage =
            runCatching { storageRoot.canonicalFile }
                .getOrElse {
                    return WindowsPrefixPlan(
                        valid = false,
                        layout = null,
                        blockers = listOf("WINDOWS_PREFIX_STORAGE_ROOT_INVALID"),
                    )
                }

        if (blockers.isNotEmpty()) {
            return WindowsPrefixPlan(
                valid = false,
                layout = null,
                blockers = blockers,
            )
        }

        val prefixesRoot =
            File(canonicalStorage, "windows-prefixes")
        val prefixRoot =
            File(prefixesRoot, profileId)

        val canonicalPrefix =
            runCatching { prefixRoot.canonicalFile }
                .getOrElse {
                    return WindowsPrefixPlan(
                        valid = false,
                        layout = null,
                        blockers = listOf("WINDOWS_PREFIX_PATH_INVALID"),
                    )
                }

        val expectedParent =
            runCatching { prefixesRoot.canonicalFile }
                .getOrElse {
                    return WindowsPrefixPlan(
                        valid = false,
                        layout = null,
                        blockers = listOf("WINDOWS_PREFIX_PARENT_INVALID"),
                    )
                }

        if (canonicalPrefix.parentFile != expectedParent) {
            return WindowsPrefixPlan(
                valid = false,
                layout = null,
                blockers = listOf("WINDOWS_PREFIX_PATH_ESCAPED_STORAGE"),
            )
        }

        val driveC = File(canonicalPrefix, "drive_c")
        val users = File(driveC, "users")
        val pocketUser = File(users, "pocket")

        return WindowsPrefixPlan(
            valid = true,
            layout =
                WindowsPrefixLayout(
                    profileId = profileId,
                    prefixRoot = canonicalPrefix,
                    driveC = driveC,
                    usersRoot = users,
                    pocketUser = pocketUser,
                    temp = File(pocketUser, "Temp"),
                    dosDevices = File(canonicalPrefix, "dosdevices"),
                    systemRegistry = File(canonicalPrefix, "system.reg"),
                    userRegistry = File(canonicalPrefix, "user.reg"),
                    userDefRegistry = File(canonicalPrefix, "userdef.reg"),
                ),
            blockers = emptyList(),
        )
    }
}
