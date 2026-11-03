package dev.pocketpc.core.ui

import java.net.URI
import java.util.Locale

/**
 * A web-based remote desktop gateway (for example noVNC or Guacamole).
 * This is NOT a native RDP/VNC client and does not execute Windows locally.
 */
internal data class RemoteDesktopEndpoint(val url: String) {
    companion object {
        fun fromUserInput(input: String): RemoteDesktopEndpoint? {
            val normalized = input.trim()
            if (normalized.length !in 8..2048 || normalized.any { it.isWhitespace() || it.isISOControl() }) {
                return null
            }
            val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
            if (!safeWebUri(uri) || uri.rawUserInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null
            ) {
                // Do not persist URLs containing passwords, invitation tokens or fragments.
                return null
            }
            return RemoteDesktopEndpoint(uri.normalize().toASCIIString())
        }

        fun isSafeNavigation(input: String): Boolean {
            val uri = runCatching { URI(input) }.getOrNull() ?: return false
            return safeWebUri(uri) && uri.rawUserInfo == null
        }

        private fun safeWebUri(uri: URI): Boolean {
            if (!uri.isAbsolute || uri.isOpaque || uri.port == 0 || uri.port > 65535) return false
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
            val host = uri.host?.lowercase(Locale.ROOT) ?: return false
            if (host.isBlank()) return false
            val loopback = host == "localhost" || host == "127.0.0.1" ||
                host == "[::1]" || host == "::1"
            return scheme == "https" || (scheme == "http" && loopback)
        }
    }
}
