package dev.pocketpc.core.desktop

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.view.Display

data class DesktopDisplayInfo(
    val displayId: Int,
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val refreshRateHz: Float,
    val presentation: Boolean,
    val state: Int,
)

data class DesktopCapabilitySnapshot(
    val secondaryDisplayActivities: Boolean,
    val externalDisplays: List<DesktopDisplayInfo>,
) {
    val externalDisplayCount: Int
        get() = externalDisplays.size

    val presentationDisplayCount: Int
        get() = externalDisplays.count { it.presentation }
}

object DesktopCapabilityProbe {
    fun inspect(context: Context): DesktopCapabilitySnapshot {
        val packageManager = context.packageManager
        val displayManager =
            context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        val presentationIds = displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .map { display -> display.displayId }
            .toSet()

        val displays = displayManager.displays
            .filterNot { display -> display.displayId == Display.DEFAULT_DISPLAY }
            .map { display ->
                val mode = display.mode
                DesktopDisplayInfo(
                    displayId = display.displayId,
                    name = display.name,
                    widthPx = mode.physicalWidth,
                    heightPx = mode.physicalHeight,
                    refreshRateHz = mode.refreshRate,
                    presentation = display.displayId in presentationIds,
                    state = display.state,
                )
            }
            .sortedBy { it.displayId }

        return DesktopCapabilitySnapshot(
            secondaryDisplayActivities =
                packageManager.hasSystemFeature(
                    PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS
                ),
            externalDisplays = displays,
        )
    }
}
