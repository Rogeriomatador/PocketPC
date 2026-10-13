package dev.pocketpc.core.runtime

data class RobloxPlayerDeepLink private constructor(
    val raw: String,
) {
    companion object {
        private const val SCHEME = "roblox-player:"
        private const val MAX_LENGTH = 8_192

        fun parse(value: String): RobloxPlayerDeepLink? {
            if (value.isEmpty() || value.length > MAX_LENGTH) {
                return null
            }
            if (value != value.trim()) {
                return null
            }
            if (!value.startsWith(SCHEME, ignoreCase = true)) {
                return null
            }
            if (value.length == SCHEME.length) {
                return null
            }
            if (
                value.any { character ->
                    character == '\u0000' ||
                        character == '\r' ||
                        character == '\n' ||
                        character.code < 0x20 ||
                        character.code == 0x7f
                }
            ) {
                return null
            }

            return RobloxPlayerDeepLink(value)
        }
    }
}

enum class PocketBrowserExternalRouteKind {
    ROBLOX_PLAYER,
    BLOCKED_EXTERNAL,
}

data class PocketBrowserExternalRoute(
    val kind: PocketBrowserExternalRouteKind,
    val robloxDeepLink: RobloxPlayerDeepLink? = null,
    val detail: String,
)

object PocketBrowserExternalRouteProbe {
    fun route(uri: String): PocketBrowserExternalRoute {
        val roblox = RobloxPlayerDeepLink.parse(uri)
        if (roblox != null) {
            return PocketBrowserExternalRoute(
                kind = PocketBrowserExternalRouteKind.ROBLOX_PLAYER,
                robloxDeepLink = roblox,
                detail =
                    "Deep link Roblox Player reconhecido para roteamento interno do PocketPC.",
            )
        }

        return PocketBrowserExternalRoute(
            kind = PocketBrowserExternalRouteKind.BLOCKED_EXTERNAL,
            detail =
                "Esquema externo não reconhecido; saída automática para Android permanece bloqueada.",
        )
    }
}
