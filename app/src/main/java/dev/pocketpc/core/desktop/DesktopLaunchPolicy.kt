package dev.pocketpc.core.desktop

data class DesktopLaunchPlan(
    val requestedDisplayId: Int?,
    val useFreeformBounds: Boolean,
) {
    val usesExternalDisplay: Boolean
        get() = requestedDisplayId != null

    val usesDesktopWindowing: Boolean
        get() = useFreeformBounds
}

object DesktopLaunchPolicy {
    fun plan(
        capabilities: DesktopCapabilitySnapshot,
        preferExternal: Boolean,
        preferWindowed: Boolean,
    ): DesktopLaunchPlan =
        DesktopLaunchPlan(
            requestedDisplayId =
                if (preferExternal) {
                    capabilities.preferredExternalDisplayId
                } else {
                    null
                },
            useFreeformBounds =
                preferWindowed &&
                    capabilities.freeformWindowManagement,
        )
}
