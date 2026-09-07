package dev.pocketpc.core.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopLaunchPolicyTest {
    private fun capabilities(
        freeform: Boolean,
        displays: List<DesktopDisplayInfo> = emptyList(),
    ): DesktopCapabilitySnapshot =
        DesktopCapabilitySnapshot(
            secondaryDisplayActivities = displays.isNotEmpty(),
            freeformWindowManagement = freeform,
            pcHardwareType = false,
            externalDisplays = displays,
        )

    private fun display(
        id: Int,
        presentation: Boolean,
    ): DesktopDisplayInfo =
        DesktopDisplayInfo(
            displayId = id,
            name = "Synthetic-$id",
            widthPx = 1920,
            heightPx = 1080,
            refreshRateHz = 60f,
            presentation = presentation,
            state = 2,
        )

    @Test
    fun noCapabilitiesFallsBackToCurrentDisplayWithoutFreeform() {
        val plan = DesktopLaunchPolicy.plan(
            capabilities = capabilities(freeform = false),
            preferExternal = true,
            preferWindowed = true,
        )

        assertNull(plan.requestedDisplayId)
        assertFalse(plan.useFreeformBounds)
        assertFalse(plan.usesExternalDisplay)
        assertFalse(plan.usesDesktopWindowing)
    }

    @Test
    fun presentationDisplayIsPreferredOverGenericExternalDisplay() {
        val plan = DesktopLaunchPolicy.plan(
            capabilities = capabilities(
                freeform = true,
                displays = listOf(
                    display(id = 4, presentation = false),
                    display(id = 7, presentation = true),
                ),
            ),
            preferExternal = true,
            preferWindowed = true,
        )

        assertEquals(7, plan.requestedDisplayId)
        assertTrue(plan.useFreeformBounds)
    }

    @Test
    fun userCanKeepExternalDisplayButDisableFreeformBounds() {
        val plan = DesktopLaunchPolicy.plan(
            capabilities = capabilities(
                freeform = true,
                displays = listOf(display(id = 9, presentation = true)),
            ),
            preferExternal = true,
            preferWindowed = false,
        )

        assertEquals(9, plan.requestedDisplayId)
        assertFalse(plan.useFreeformBounds)
    }

    @Test
    fun userCanPreferCurrentDisplayWhileStillRequestingFreeform() {
        val plan = DesktopLaunchPolicy.plan(
            capabilities = capabilities(
                freeform = true,
                displays = listOf(display(id = 11, presentation = true)),
            ),
            preferExternal = false,
            preferWindowed = true,
        )

        assertNull(plan.requestedDisplayId)
        assertTrue(plan.useFreeformBounds)
    }
}
