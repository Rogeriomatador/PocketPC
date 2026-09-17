package dev.pocketpc.core.runtime

enum class HardwareBufferProbeSide(
    val prefix: String,
) {
    SEND("ahb-xproc-send"),
    RECEIVE("ahb-xproc-recv"),
}

data class HardwareBufferProbeRecord(
    val side: HardwareBufferProbeSide,
    val status: String,
    val protocol: Int,
    val pid: Int,
    val width: Int?,
    val height: Int?,
    val layers: Int?,
    val format: Int?,
    val stride: Int?,
    val operationResult: Int?,
    val lockResult: Int?,
    val unlockResult: Int?,
    val descriptorMatch: Boolean?,
    val patternMatch: Boolean?,
    val raw: String,
) {
    val successful: Boolean
        get() =
            status == "ok" &&
                protocol == CURRENT_PROTOCOL &&
                pid > 0 &&
                width == EXPECTED_WIDTH &&
                height == EXPECTED_HEIGHT &&
                layers == EXPECTED_LAYERS &&
                format != null &&
                format > 0 &&
                stride != null &&
                stride >= EXPECTED_WIDTH &&
                lockResult == 0 &&
                unlockResult == 0 &&
                operationResult == 0 &&
                when (side) {
                    HardwareBufferProbeSide.SEND -> true
                    HardwareBufferProbeSide.RECEIVE ->
                        descriptorMatch == true &&
                            patternMatch == true
                }

    companion object {
        const val CURRENT_PROTOCOL = 1
        const val EXPECTED_WIDTH = 64
        const val EXPECTED_HEIGHT = 64
        const val EXPECTED_LAYERS = 1
    }
}

object HardwareBufferProbeProtocol {
    fun parse(
        raw: String,
        expectedSide: HardwareBufferProbeSide,
    ): HardwareBufferProbeRecord? {
        if (raw.isBlank() || raw.length > MAX_RECORD_LENGTH) {
            return null
        }
        if (raw.any { it == '\u0000' || it == '\r' || it == '\n' }) {
            return null
        }

        val fields = LinkedHashMap<String, String>()
        for (token in raw.split(';')) {
            val separator = token.indexOf('=')
            if (separator <= 0 || separator == token.lastIndex) {
                return null
            }
            val key = token.substring(0, separator)
            val value = token.substring(separator + 1)
            if (!FIELD_NAME.matches(key) || key in fields) {
                return null
            }
            fields[key] = value
        }

        if (fields.keys.any { it !in ALLOWED_FIELDS }) {
            return null
        }

        val oppositeSide =
            when (expectedSide) {
                HardwareBufferProbeSide.SEND ->
                    HardwareBufferProbeSide.RECEIVE
                HardwareBufferProbeSide.RECEIVE ->
                    HardwareBufferProbeSide.SEND
            }
        if (oppositeSide.prefix in fields) {
            return null
        }

        val status = fields[expectedSide.prefix] ?: return null
        if (!STATUS.matches(status)) {
            return null
        }
        val protocol = fields["protocol"]?.toIntOrNull() ?: return null
        val pid = fields["pid"]?.toIntOrNull() ?: return null

        fun intField(name: String): Int? =
            fields[name]?.toIntOrNull()

        fun booleanField(name: String): Boolean? =
            when (fields[name]) {
                null -> null
                "yes" -> true
                "no" -> false
                else -> return null
            }

        val operationResult =
            when (expectedSide) {
                HardwareBufferProbeSide.SEND -> intField("send")
                HardwareBufferProbeSide.RECEIVE -> intField("recv")
            }

        return HardwareBufferProbeRecord(
            side = expectedSide,
            status = status,
            protocol = protocol,
            pid = pid,
            width = intField("width"),
            height = intField("height"),
            layers = intField("layers"),
            format = intField("format"),
            stride = intField("stride"),
            operationResult = operationResult,
            lockResult = intField("lock"),
            unlockResult = intField("unlock"),
            descriptorMatch = booleanField("descriptor_match"),
            patternMatch = booleanField("pattern_match"),
            raw = raw,
        )
    }

    private const val MAX_RECORD_LENGTH = 2_048
    private val FIELD_NAME = Regex("^[a-z0-9_-]{1,64}$")
    private val STATUS = Regex("^[a-z0-9-]{1,64}$")
    private val ALLOWED_FIELDS =
        setOf(
            "ahb-xproc-send",
            "ahb-xproc-recv",
            "protocol",
            "pid",
            "width",
            "height",
            "layers",
            "format",
            "stride",
            "lock",
            "unlock",
            "send",
            "recv",
            "allocate",
            "descriptor_match",
            "pattern_match",
            "error",
        )
}
