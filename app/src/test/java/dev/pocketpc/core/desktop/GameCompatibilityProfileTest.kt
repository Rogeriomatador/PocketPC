package dev.pocketpc.core.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameCompatibilityProfileTest {
    @Test
    fun newGameStartsUntestedWithoutConfirmedDesktopInput() {
        val profile = GameCompatibilityProfile(
            packageName = "com.example.game",
        )

        assertEquals(GameDesktopRating.UNTESTED, profile.rating)
        assertFalse(profile.hasAnyConfirmedDesktopInput)
    }

    @Test
    fun anyConfirmedInputMarksDesktopInputAsObserved() {
        val mouse = GameCompatibilityProfile(
            packageName = "com.example.mouse",
            mouseConfirmed = true,
        )
        val keyboard = GameCompatibilityProfile(
            packageName = "com.example.keyboard",
            keyboardConfirmed = true,
        )
        val gamepad = GameCompatibilityProfile(
            packageName = "com.example.gamepad",
            gamepadConfirmed = true,
        )

        assertTrue(mouse.hasAnyConfirmedDesktopInput)
        assertTrue(keyboard.hasAnyConfirmedDesktopInput)
        assertTrue(gamepad.hasAnyConfirmedDesktopInput)
    }

    @Test
    fun optimizedRatingDoesNotFabricateInputEvidence() {
        val profile = GameCompatibilityProfile(
            packageName = "com.example.optimized",
            rating = GameDesktopRating.OPTIMIZED,
        )

        assertEquals(GameDesktopRating.OPTIMIZED, profile.rating)
        assertFalse(profile.mouseConfirmed)
        assertFalse(profile.keyboardConfirmed)
        assertFalse(profile.gamepadConfirmed)
        assertFalse(profile.externalDisplayConfirmed)
        assertFalse(profile.hasAnyConfirmedDesktopInput)
    }
}
