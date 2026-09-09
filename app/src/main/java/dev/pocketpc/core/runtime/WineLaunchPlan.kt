package dev.pocketpc.core.runtime

data class WineLaunchPlan(
    val ready: Boolean,
    val argv: List<String>,
    val environment: Map<String, String>,
    val blockers: List<String>,
)

object WineLaunchPlanner {
    const val DEFAULT_BOX64 = "/opt/pocketpc/box64/bin/box64"
    const val DEFAULT_WINE = "/opt/pocketpc/wine/bin/wine"
    const val DXVK_DLL_OVERRIDES = "d3d11=n;dxgi=n"

    fun build(
        prefixPlan: WindowsPrefixPlan,
        windowsExecutable: String,
        windowsArgs: List<String> = emptyList(),
        box64RuntimeValidated: Boolean,
        wineRuntimeValidated: Boolean,
        box64GuestPath: String = DEFAULT_BOX64,
        wineGuestPath: String = DEFAULT_WINE,
        enableDxvk: Boolean = false,
    ): WineLaunchPlan {
        val blockers = mutableListOf<String>()

        val prefix = prefixPlan.layout
        if (!prefixPlan.valid || prefix == null) {
            blockers += prefixPlan.blockers.ifEmpty {
                listOf("WINDOWS_PREFIX_NOT_READY")
            }
        }
        if (!box64RuntimeValidated) {
            blockers += "BOX64_RUNTIME_NOT_VALIDATED"
        }
        if (!wineRuntimeValidated) {
            blockers += "WINE_RUNTIME_NOT_VALIDATED"
        }

        if (!isSafeGuestAbsolutePath(box64GuestPath)) {
            blockers += "BOX64_GUEST_PATH_INVALID"
        }
        if (!isSafeGuestAbsolutePath(wineGuestPath)) {
            blockers += "WINE_GUEST_PATH_INVALID"
        }
        if (!isSafeArgument(windowsExecutable) || windowsExecutable.isBlank()) {
            blockers += "WINDOWS_EXECUTABLE_INVALID"
        }
        if (windowsArgs.size > 64) {
            blockers += "WINDOWS_ARGUMENT_LIMIT_EXCEEDED"
        }
        if (windowsArgs.any { !isSafeArgument(it) }) {
            blockers += "WINDOWS_ARGUMENT_INVALID"
        }

        val environment =
            if (prefix == null) {
                emptyMap()
            } else {
                linkedMapOf(
                    "WINEPREFIX" to prefix.guestPrefixRoot,
                    "WINEARCH" to "win64",
                    "HOME" to "/home/pocket",
                    "TMPDIR" to "/tmp",
                ).apply {
                    if (enableDxvk) {
                        put(
                            "WINEDLLOVERRIDES",
                            DXVK_DLL_OVERRIDES,
                        )
                    }
                }
            }

        val argv =
            if (blockers.isEmpty()) {
                buildList {
                    add(box64GuestPath)
                    add(wineGuestPath)
                    add(windowsExecutable)
                    addAll(windowsArgs)
                }
            } else {
                emptyList()
            }

        return WineLaunchPlan(
            ready = blockers.isEmpty(),
            argv = argv,
            environment = environment,
            blockers = blockers.distinct(),
        )
    }

    private fun isSafeGuestAbsolutePath(value: String): Boolean =
        value.startsWith("/") &&
            '\u0000' !in value &&
            '\n' !in value &&
            value.length <= 4096 &&
            RuntimeBindPolicy.normalizeGuestPath(value) == value

    private fun isSafeArgument(value: String): Boolean =
        '\u0000' !in value &&
            '\n' !in value &&
            value.length <= 16_384
}
