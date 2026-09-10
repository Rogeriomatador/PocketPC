package dev.pocketpc.core.runtime

import android.content.Context

class RobloxRuntimeEvidenceStore(
    context: Context,
) {
    private val prefs =
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE,
        )

    fun stateFor(
        fingerprint: RobloxExecutionFingerprint,
    ): RobloxRuntimeEvidence {
        if (
            prefs.getString(
                KEY_FINGERPRINT,
                null,
            ) != fingerprint.value
        ) {
            return RobloxRuntimeEvidence()
        }

        return RobloxRuntimeEvidence(
            playerProcessStarted =
                prefs.getBoolean(
                    KEY_PROCESS,
                    false,
                ),
            playerWindowPresented =
                prefs.getBoolean(
                    KEY_WINDOW,
                    false,
                ),
            d3d11PresentObserved =
                prefs.getBoolean(
                    KEY_PRESENT,
                    false,
                ),
            externalNetworkObserved =
                prefs.getBoolean(
                    KEY_NETWORK,
                    false,
                ),
            audioOutputObserved =
                prefs.getBoolean(
                    KEY_AUDIO,
                    false,
                ),
            pointerInputObserved =
                prefs.getBoolean(
                    KEY_POINTER,
                    false,
                ),
            keyboardInputObserved =
                prefs.getBoolean(
                    KEY_KEYBOARD,
                    false,
                ),
            stableSessionMillis =
                prefs.getLong(
                    KEY_STABLE_SESSION,
                    0L,
                ).coerceAtLeast(0L),
            crashObserved =
                prefs.getBoolean(
                    KEY_CRASH,
                    false,
                ),
        )
    }

    fun replace(
        fingerprint: RobloxExecutionFingerprint,
        evidence: RobloxRuntimeEvidence,
    ) {
        require(
            FINGERPRINT.matches(
                fingerprint.value,
            ),
        ) {
            "Fingerprint Roblox inválida."
        }
        require(
            evidence.stableSessionMillis >= 0L,
        ) {
            "Duração da sessão Roblox inválida."
        }

        prefs.edit()
            .clear()
            .putString(
                KEY_FINGERPRINT,
                fingerprint.value,
            )
            .putString(
                KEY_CLIENT_SHA256,
                fingerprint.client
                    .executableSha256,
            )
            .putString(
                KEY_RUNTIME_IDENTITY,
                fingerprint.runtimeIdentity,
            )
            .putString(
                KEY_LAYER_IDENTITY,
                fingerprint.layerIdentity,
            )
            .putString(
                KEY_GUEST_PREFIX,
                fingerprint.guestPrefixRoot,
            )
            .putBoolean(
                KEY_PROCESS,
                evidence.playerProcessStarted,
            )
            .putBoolean(
                KEY_WINDOW,
                evidence.playerWindowPresented,
            )
            .putBoolean(
                KEY_PRESENT,
                evidence.d3d11PresentObserved,
            )
            .putBoolean(
                KEY_NETWORK,
                evidence.externalNetworkObserved,
            )
            .putBoolean(
                KEY_AUDIO,
                evidence.audioOutputObserved,
            )
            .putBoolean(
                KEY_POINTER,
                evidence.pointerInputObserved,
            )
            .putBoolean(
                KEY_KEYBOARD,
                evidence.keyboardInputObserved,
            )
            .putLong(
                KEY_STABLE_SESSION,
                evidence.stableSessionMillis,
            )
            .putBoolean(
                KEY_CRASH,
                evidence.crashObserved,
            )
            .apply()
    }

    fun clear() {
        prefs.edit()
            .clear()
            .apply()
    }

    companion object {
        private const val PREFS =
            "pocketpc.roblox.runtime.evidence.v1"
        private const val KEY_FINGERPRINT =
            "fingerprint"
        private const val KEY_CLIENT_SHA256 =
            "client_sha256"
        private const val KEY_RUNTIME_IDENTITY =
            "runtime_identity"
        private const val KEY_LAYER_IDENTITY =
            "layer_identity"
        private const val KEY_GUEST_PREFIX =
            "guest_prefix"
        private const val KEY_PROCESS =
            "player_process_started"
        private const val KEY_WINDOW =
            "player_window_presented"
        private const val KEY_PRESENT =
            "d3d11_present_observed"
        private const val KEY_NETWORK =
            "external_network_observed"
        private const val KEY_AUDIO =
            "audio_output_observed"
        private const val KEY_POINTER =
            "pointer_input_observed"
        private const val KEY_KEYBOARD =
            "keyboard_input_observed"
        private const val KEY_STABLE_SESSION =
            "stable_session_millis"
        private const val KEY_CRASH =
            "crash_observed"

        private val FINGERPRINT =
            Regex("^[0-9a-f]{64}$")
    }
}
