package dev.pocketpc.core.desktop

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    val freeformWindowManagement: Boolean,
    val externalDisplays: List<DesktopDisplayInfo>,
) {
    val externalDisplayCount: Int
        get() = externalDisplays.size

    val presentationDisplayCount: Int
        get() = externalDisplays.count { it.presentation }

    val preferredExternalDisplayId: Int?
        get() = externalDisplays
            .firstOrNull { it.presentation }
            ?.displayId
            ?: externalDisplays.firstOrNull()?.displayId
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
            freeformWindowManagement =
                packageManager.hasSystemFeature(
                    PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT
                ),
            externalDisplays = displays,
        )
    }
}

class DesktopCapabilityMonitor(context: Context) : DisplayManager.DisplayListener {
    private val appContext = context.applicationContext
    private val displayManager =
        appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private val mutableState =
        MutableStateFlow(DesktopCapabilityProbe.inspect(appContext))
    val state: StateFlow<DesktopCapabilitySnapshot> = mutableState.asStateFlow()

    fun start() {
        displayManager.registerDisplayListener(this, null)
        refresh()
    }

    fun stop() {
        displayManager.unregisterDisplayListener(this)
    }

    override fun onDisplayAdded(displayId: Int) = refresh()

    override fun onDisplayRemoved(displayId: Int) = refresh()

    override fun onDisplayChanged(displayId: Int) = refresh()

    private fun refresh() {
        mutableState.value = DesktopCapabilityProbe.inspect(appContext)
    }
}
