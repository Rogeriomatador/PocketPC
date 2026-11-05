package dev.pocketpc.core.runtime

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Compact physical-test evidence channel for the graphics transport.
 *
 * It deliberately excludes socket names, authentication tokens, file
 * descriptors, pointers and Vulkan handles. The lines are suitable for ADB
 * logcat collection and describe only host-observed milestones. Source code
 * emitting an event is never itself treated as executed evidence.
 */
object RuntimeGraphicsEvidenceLog {
    const val TAG = "PocketPCGraphics"
    private val lastComposeFrameByWindow =
        ConcurrentHashMap<Long, RuntimeDisplayExternalFrameIdentity>()

    fun authenticated() =
        emit("PGH1_AUTHENTICATED")

    fun resourceOffered(
        resourceId: Long,
        generation: Long,
        width: Int,
        height: Int,
    ) =
        emit(
            "RESOURCE_OFFERED" +
                " resource_id=" + resourceId +
                " generation=" + generation +
                " width=" + width +
                " height=" + height,
        )

    fun guestImportConfirmed(
        resourceId: Long,
        generation: Long,
    ) =
        emit(
            "GUEST_IMPORT_CONFIRMED" +
                " resource_id=" + resourceId +
                " generation=" + generation,
        )

    fun presentQueueSignalObserved(
        resourceId: Long,
        generation: Long,
        queueFamilyIndex: Int,
    ) =
        emit(
            "PRESENT_QUEUE_SIGNAL_OBSERVED" +
                " resource_id=" + resourceId +
                " generation=" + generation +
                " queue_family=" + queueFamilyIndex +
                " host_visible_frame=0",
        )

    fun presentCopyCompleted(
        resourceId: Long,
        generation: Long,
        sequence: Long,
        queueFamilyIndex: Int,
    ) =
        emit(
            "PRESENT_COPY_COMPLETED" +
                " resource_id=" + resourceId +
                " generation=" + generation +
                " sequence=" + sequence +
                " queue_family=" + queueFamilyIndex +
                " returned_external_general=1" +
                " host_visible_frame=0" +
                " roblox_validated=0",
        )

    fun androidHostReadbackCompleted(
        resourceId: Long,
        generation: Long,
        sequence: Long,
        timelineValue: Long,
        bytes: Long,
        nonzeroBytes: Long,
        fnv1a64: ULong,
    ) =
        emit(
            "ANDROID_HOST_READBACK_COMPLETED" +
                " resource_id=" + resourceId +
                " generation=" + generation +
                " sequence=" + sequence +
                " timeline_value=" + timelineValue +
                " bytes=" + bytes +
                " nonzero_bytes=" + nonzeroBytes +
                " fnv1a64=" + fnv1a64 +
                " returned_external_general=1" +
                " host_visible_frame=0" +
                " roblox_validated=0",
        )

    fun desktopModelFrameDelivered(
        windowId: Long,
        resourceId: Long,
        generation: Long,
        sequence: Long,
        width: Int,
        height: Int,
    ) =
        emit(
            "DESKTOP_MODEL_FRAME_DELIVERED" +
                " window_id=" + windowId +
                " resource_id=" + resourceId +
                " generation=" + generation +
                " sequence=" + sequence +
                " width=" + width +
                " height=" + height +
                " model_delivery=1" +
                " host_visible_frame=0" +
                " physical_validated=0" +
                " roblox_validated=0",
        )

    fun composeFrameDrawSubmitted(
        windowId: Long,
        identity: RuntimeDisplayExternalFrameIdentity,
        width: Int,
        height: Int,
    ) {
        if (windowId <= 0L || !identity.structurallyValid || width <= 0 || height <= 0) return
        if (lastComposeFrameByWindow.put(windowId, identity) == identity) return
        emit(
            "COMPOSE_FRAME_DRAW_SUBMITTED" +
                " window_id=" + windowId +
                " resource_id=" + identity.resourceId +
                " generation=" + identity.generation +
                " sequence=" + identity.sequence +
                " width=" + width +
                " height=" + height +
                " compose_draw=1" +
                " host_visible_frame=0" +
                " physical_validated=0" +
                " roblox_validated=0",
        )
    }

    fun blocked(blocker: String) {
        if (blocker.isBlank()) return
        val safe =
            blocker
                .take(160)
                .filter { it.code in 0x20..0x7e && it != '\n' && it != '\r' }
        if (safe.isNotBlank()) emit("BLOCKED blocker=$safe")
    }

    private fun emit(message: String) {
        Log.i(TAG, "POCKETPC_GRAPHICS_EVIDENCE $message")
    }
}
