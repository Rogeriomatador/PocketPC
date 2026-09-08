package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestProbeRequirementsTest {
    @Test
    fun basicLinuxProbesNeedNoGuestTools() {
        assertTrue(
            GuestProbeRequirements
                .requiredToolIds(
                    GuestRuntimeProbe.SHELL,
                ).isEmpty(),
        )
        assertTrue(
            GuestProbeRequirements
                .requiredToolIds(
                    GuestRuntimeProbe.ROOTFS,
                ).isEmpty(),
        )
    }

    @Test
    fun box64SmokeRequiresOnlyBox64() {
        assertEquals(
            setOf("box64"),
            GuestProbeRequirements
                .requiredToolIds(
                    GuestRuntimeProbe.BOX64_SMOKE,
                ),
        )
    }

    @Test
    fun wineSmokeRequiresBox64AndWine() {
        assertEquals(
            setOf("box64", "wine"),
            GuestProbeRequirements
                .requiredToolIds(
                    GuestRuntimeProbe.WINE_SMOKE,
                ),
        )
        val blockers =
            GuestProbeRequirements.blockers(
                probe = GuestRuntimeProbe.WINE_SMOKE,
                installedToolIds = setOf("box64"),
                overlayValid = true,
            )
        assertEquals(
            listOf("GUEST_TOOL_REQUIRED_MISSING:wine"),
            blockers,
        )
    }

    @Test
    fun invalidOverlayFailsClosedBeforeAdvancedProbe() {
        val blockers =
            GuestProbeRequirements.blockers(
                probe = GuestRuntimeProbe.BOX64_SMOKE,
                installedToolIds = setOf("box64"),
                overlayValid = false,
            )

        assertTrue(
            blockers.contains(
                "GUEST_TOOL_OVERLAY_NOT_ATTESTED",
            ),
        )
    }
}
