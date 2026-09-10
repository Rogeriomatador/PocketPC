package dev.pocketpc.core.runtime

enum class RobloxObservedSignal {
    PLAYER_PROCESS_STARTED,
    PLAYER_WINDOW_PRESENTED,
    D3D11_PRESENT_OBSERVED,
    EXTERNAL_NETWORK_OBSERVED,
    AUDIO_OUTPUT_OBSERVED,
    POINTER_INPUT_OBSERVED,
    KEYBOARD_INPUT_OBSERVED,
    CRASH_OBSERVED,
}

class RobloxRuntimeEvidenceAccumulator(
    private val fingerprint: String,
    private val startedAtElapsedMillis: Long,
) {
    init {
        require(FINGERPRINT.matches(fingerprint)) {
            "Fingerprint Roblox inválida."
        }
        require(startedAtElapsedMillis >= 0L) {
            "Tempo inicial monotônico inválido."
        }
    }

    private var processStarted = false
    private var processAlive = false
    private var processStartedAtElapsedMillis: Long? = null
    private var windowPresented = false
    private var d3d11PresentObserved = false
    private var networkObserved = false
    private var audioObserved = false
    private var pointerObserved = false
    private var keyboardObserved = false
    private var crashObserved = false
    private var stoppedAtElapsedMillis: Long? = null

    fun observe(
        signal: RobloxObservedSignal,
        elapsedMillis: Long,
    ) {
        require(elapsedMillis >= startedAtElapsedMillis) {
            "Evento Roblox anterior ao início da sessão."
        }

        when (signal) {
            RobloxObservedSignal.PLAYER_PROCESS_STARTED -> {
                if (!processStarted) {
                    processStarted = true
                    processStartedAtElapsedMillis =
                        elapsedMillis
                }
                processAlive = true
                stoppedAtElapsedMillis = null
            }
            RobloxObservedSignal.PLAYER_WINDOW_PRESENTED ->
                if (processStarted) {
                    windowPresented = true
                }
            RobloxObservedSignal.D3D11_PRESENT_OBSERVED ->
                if (processStarted) {
                    d3d11PresentObserved = true
                }
            RobloxObservedSignal.EXTERNAL_NETWORK_OBSERVED ->
                if (processStarted) {
                    networkObserved = true
                }
            RobloxObservedSignal.AUDIO_OUTPUT_OBSERVED ->
                if (processStarted) {
                    audioObserved = true
                }
            RobloxObservedSignal.POINTER_INPUT_OBSERVED ->
                if (processStarted) {
                    pointerObserved = true
                }
            RobloxObservedSignal.KEYBOARD_INPUT_OBSERVED ->
                if (processStarted) {
                    keyboardObserved = true
                }
            RobloxObservedSignal.CRASH_OBSERVED -> {
                crashObserved = true
                if (processStarted && processAlive) {
                    processAlive = false
                    stoppedAtElapsedMillis = elapsedMillis
                }
            }
        }
    }

    fun processExited(
        elapsedMillis: Long,
        crashed: Boolean,
    ) {
        require(elapsedMillis >= startedAtElapsedMillis) {
            "Saída Roblox anterior ao início da sessão."
        }
        if (!processStarted) {
            return
        }
        processAlive = false
        if (stoppedAtElapsedMillis == null) {
            stoppedAtElapsedMillis = elapsedMillis
        }
        if (crashed) {
            crashObserved = true
        }
    }

    fun snapshot(
        expectedFingerprint: String,
        elapsedMillis: Long,
    ): RobloxRuntimeEvidence {
        require(expectedFingerprint == fingerprint) {
            "Fingerprint Roblox mudou durante a coleta de evidência."
        }
        require(elapsedMillis >= startedAtElapsedMillis) {
            "Tempo de snapshot Roblox inválido."
        }

        val processStart =
            processStartedAtElapsedMillis
        val effectiveEnd =
            if (processStart != null) {
                stoppedAtElapsedMillis
                    ?: elapsedMillis
            } else {
                null
            }
        val stableMillis =
            if (
                processStart != null &&
                effectiveEnd != null
            ) {
                (effectiveEnd - processStart)
                    .coerceAtLeast(0L)
            } else {
                0L
            }

        return RobloxRuntimeEvidence(
            playerProcessStarted = processStarted,
            playerWindowPresented = windowPresented,
            d3d11PresentObserved = d3d11PresentObserved,
            externalNetworkObserved = networkObserved,
            audioOutputObserved = audioObserved,
            pointerInputObserved = pointerObserved,
            keyboardInputObserved = keyboardObserved,
            stableSessionMillis = stableMillis,
            crashObserved = crashObserved,
        )
    }

    companion object {
        private val FINGERPRINT =
            Regex("^[0-9a-f]{64}$")
    }
}
