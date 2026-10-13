package dev.pocketpc.core.runtime

import android.content.Context
import java.security.MessageDigest

data class PcApplicationLaunchProfile(
    val graphics: PcApplicationGraphicsProfile,
    val arguments: List<String> = emptyList(),
    val argumentsText: String = "",
)

/** Profiles are preferences, never runtime execution evidence. */
class PcApplicationLaunchProfileStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("pc-launch-profiles-v1", Context.MODE_PRIVATE)

    fun load(target: PcApplicationTarget): PcApplicationLaunchProfile {
        val key = key(target.uri)
        val graphics = preferences.getString("$key.graphics", null)
            ?.let { stored -> PcApplicationGraphicsProfile.entries.firstOrNull { it.name == stored } }
            ?: target.graphicsProfile
        val text = preferences.getString("$key.arguments", "").orEmpty()
        val arguments = PcLaunchArgumentParser.parse(text).getOrDefault(emptyList())
        return PcApplicationLaunchProfile(PcApplicationGraphicsProfile.resolve(target.fileName, graphics), arguments, text)
    }

    fun save(target: PcApplicationTarget, graphics: PcApplicationGraphicsProfile, argumentsText: String): Result<Unit> =
        PcLaunchArgumentParser.parse(argumentsText).mapCatching {
            val key = key(target.uri)
            check(preferences.edit().putString("$key.graphics", graphics.name)
                .putString("$key.arguments", argumentsText).commit()) { "PC_LAUNCH_PROFILE_SAVE_FAILED" }
        }

    private fun key(uri: String): String = MessageDigest.getInstance("SHA-256")
        .digest(uri.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}

object PcLaunchArgumentParser {
    /** Quotes group arguments. Shell operators and substitutions have no special meaning. */
    fun parse(text: String): Result<List<String>> = runCatching {
        require(text.length <= 16_384 && text.none { it == '\u0000' || it == '\n' || it == '\r' }) {
            "PC_ARGUMENT_TEXT_INVALID"
        }
        val arguments = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var started = false
        for (character in text) {
            when {
                character == quote -> quote = null
                quote != null -> current.append(character)
                character == '"' || character == '\'' -> { quote = character; started = true }
                character.isWhitespace() -> if (started) {
                    arguments += current.toString(); current.setLength(0); started = false
                }
                else -> { current.append(character); started = true }
            }
        }
        require(quote == null) { "PC_ARGUMENT_QUOTE_UNCLOSED" }
        if (started) arguments += current.toString()
        require(arguments.size <= 64) { "PC_ARGUMENT_COUNT_EXCEEDED" }
        arguments
    }
}
