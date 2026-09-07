package dev.pocketpc.core.desktop

import android.content.Context

enum class GameDesktopRating(
    val label: String,
) {
    UNTESTED("NAO TESTADO"),
    PLAYABLE("JOGAVEL"),
    OPTIMIZED("OTIMIZADO"),
    INCOMPATIBLE("INCOMPATIVEL"),
}

data class GameCompatibilityProfile(
    val packageName: String,
    val rating: GameDesktopRating = GameDesktopRating.UNTESTED,
    val mouseConfirmed: Boolean = false,
    val keyboardConfirmed: Boolean = false,
    val gamepadConfirmed: Boolean = false,
    val externalDisplayConfirmed: Boolean = false,
) {
    val hasAnyConfirmedDesktopInput: Boolean
        get() = mouseConfirmed || keyboardConfirmed || gamepadConfirmed
}

class GameCompatibilityStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            "pocketpc-game-compatibility",
            Context.MODE_PRIVATE,
        )

    fun load(packageName: String): GameCompatibilityProfile {
        val prefix = keyPrefix(packageName)
        val rating =
            runCatching {
                GameDesktopRating.valueOf(
                    preferences.getString(
                        "${prefix}rating",
                        GameDesktopRating.UNTESTED.name,
                    ) ?: GameDesktopRating.UNTESTED.name
                )
            }.getOrDefault(GameDesktopRating.UNTESTED)

        return GameCompatibilityProfile(
            packageName = packageName,
            rating = rating,
            mouseConfirmed =
                preferences.getBoolean("${prefix}mouse", false),
            keyboardConfirmed =
                preferences.getBoolean("${prefix}keyboard", false),
            gamepadConfirmed =
                preferences.getBoolean("${prefix}gamepad", false),
            externalDisplayConfirmed =
                preferences.getBoolean("${prefix}external_display", false),
        )
    }

    fun save(profile: GameCompatibilityProfile) {
        val prefix = keyPrefix(profile.packageName)
        preferences.edit()
            .putString("${prefix}rating", profile.rating.name)
            .putBoolean("${prefix}mouse", profile.mouseConfirmed)
            .putBoolean("${prefix}keyboard", profile.keyboardConfirmed)
            .putBoolean("${prefix}gamepad", profile.gamepadConfirmed)
            .putBoolean(
                "${prefix}external_display",
                profile.externalDisplayConfirmed,
            )
            .apply()
    }

    fun clear(packageName: String) {
        val prefix = keyPrefix(packageName)
        preferences.edit()
            .remove("${prefix}rating")
            .remove("${prefix}mouse")
            .remove("${prefix}keyboard")
            .remove("${prefix}gamepad")
            .remove("${prefix}external_display")
            .apply()
    }

    private fun keyPrefix(packageName: String): String =
        "game.${packageName.replace("|", "_")}."
}
