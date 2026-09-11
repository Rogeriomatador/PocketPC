#!/usr/bin/env python3
"""Static policy for the PocketPC Wine Present-queue timeline precursor."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HEADER = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_guest_vulkan_timeline_semaphore.h"
SOURCE = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_guest_vulkan_timeline_semaphore.c"
VULKAN = ROOT / "third_party/wine/pocketpc-driver/vulkan.c"
ACK_H = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_graphics_ack.h"
ACK_C = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_graphics_ack.c"
ANDROID_NATIVE = ROOT / "app/src/main/cpp/graphics_seqpacket_session.cpp"
ANDROID_HOST = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GraphicsSeqpacketSessionHost.kt"
ORCHESTRATOR = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GuestGraphicsSessionOrchestrator.kt"
DISPLAY = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeDisplayExecutionController.kt"
CONTRACT = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GuestGraphicsTransportContract.kt"
PREPARER = ROOT / "scripts/prepare-wine-pocketpc-driver.py"


def need(text: str, marker: str, label: str) -> None:
    if marker not in text:
        raise SystemExit(f"GUEST_VULKAN_GPU_QUEUE_POLICY_MISSING:{label}")


def forbid(text: str, marker: str, label: str) -> None:
    if marker in text:
        raise SystemExit(f"GUEST_VULKAN_GPU_QUEUE_POLICY_FORBIDDEN:{label}")


def main() -> int:
    header = HEADER.read_text(encoding="utf-8")
    source = SOURCE.read_text(encoding="utf-8")
    vulkan = VULKAN.read_text(encoding="utf-8")
    ack_h = ACK_H.read_text(encoding="utf-8")
    ack_c = ACK_C.read_text(encoding="utf-8")
    android_native = ANDROID_NATIVE.read_text(encoding="utf-8")
    android_host = ANDROID_HOST.read_text(encoding="utf-8")
    orchestrator = ORCHESTRATOR.read_text(encoding="utf-8")
    display = DISPLAY.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")
    preparer = PREPARER.read_text(encoding="utf-8")

    # The generic queue primitive remains reusable. The first-queue probe may
    # exist as a diagnostic helper, but the production source path below MUST
    # bind stage 5 to the exact queue supplied by win32u after Present.
    for marker, label in (
        ("pocketpc_guest_vulkan_timeline_signal_queue", "queue-signal-api"),
        ("struct vulkan_queue *queue", "wine-queue-identity"),
        ("VkTimelineSemaphoreSubmitInfo", "timeline-submit-info"),
        ("VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO", "timeline-submit-type"),
        ("device->p_vkQueueSubmit", "real-vulkan-queue-submit"),
        ("queue->host.queue", "host-queue-handle"),
        ("signalSemaphoreCount = 1u", "single-signal"),
        ("queue_signal_submitted = 1u", "queue-submit-state"),
        ("VK_NULL_HANDLE", "no-fence-shortcut"),
    ):
        need(header + "\n" + source, marker, label)

    need(ack_h, "PGA_STAGE_GPU_SIGNAL_SUBMITTED 5u", "gpu-submit-ack-stage")
    need(ack_c, "PGA_STAGE_GPU_SIGNAL_SUBMITTED", "gpu-submit-ack-accepted")

    for marker, label in (
        ("static void pocketpc_vulkan_queue_presented", "driver-present-queue-handler"),
        ("struct vulkan_queue *queue, VkResult present_result", "exact-present-queue-argument"),
        ("present_result != VK_SUCCESS && present_result != VK_SUBOPTIMAL_KHR", "successful-present-gate"),
        ("pocketpc_guest_vulkan_timeline_signal_queue", "driver-signals-pvs1-on-callback-queue"),
        ("PGA_STAGE_GPU_SIGNAL_SUBMITTED", "driver-emits-stage-five"),
        ("queue->info.queueFamilyIndex", "driver-ack-queue-family"),
        ("same_queue_as_present=1", "driver-same-present-queue-classification"),
        ("visible_present=0", "driver-visible-frame-still-false"),
        (".p_vulkan_queue_presented = pocketpc_vulkan_queue_presented,", "driver-registers-present-callback"),
    ):
        need(vulkan, marker, label)

    for marker, label in (
        ("VULKAN_DRIVER_VERSION_49", "wine-abi-v49"),
        ("p_vulkan_queue_presented", "wine-abi-present-callback"),
        ("driver_funcs->p_vulkan_queue_presented( queue, res )", "win32u-calls-present-callback"),
        ("presentQueueTimelineSignalSourceIntegrated", "overlay-source-evidence"),
    ):
        need(preparer, marker, label)

    need(android_native, "kPgaGpuSignalStage = 5", "android-stage-five-lock")
    need(android_native, "AwaitGpuSignalAck", "android-stage-five-validator")
    need(android_native, "nativeAwaitGpuSignalAck", "android-stage-five-jni")
    need(android_host, "awaitGpuQueueSignalAcknowledgement", "kotlin-stage-five-wrapper")
    need(android_host, "val presentQueueOrdered: Boolean", "kotlin-present-order-classification")
    need(android_host, "val hostVisibleFrame: Boolean", "kotlin-visible-frame-separation")
    need(orchestrator, "awaitGpuQueueSignalProbe", "orchestrator-stage-five-await")
    need(orchestrator, "PRESENT_QUEUE_SIGNAL_OBSERVED", "orchestrator-explicit-state")
    need(orchestrator, "acknowledgement.presentQueueOrdered", "orchestrator-requires-present-order")
    need(orchestrator, "!acknowledgement.hostVisibleFrame", "orchestrator-keeps-visible-frame-false")
    need(display, "graphicsGpuQueueSignalObserved", "display-observed-result")
    need(display, "graphicsGpuQueueFamilyIndex", "display-queue-family-result")

    # Source availability must never be promoted to executed synchronization.
    need(contract, "const val guestGpuQueueSignalPrimitiveImplemented = true", "contract-source-flag")
    need(contract, "const val wineVulkanAbiV49PresentQueueCallbackSourceIntegrated = true", "contract-v49-present-callback")
    need(contract, "const val presentQueueTimelineSignalSourceIntegrated = true", "contract-present-queue-source")
    need(contract, "const val guestGpuQueueSignalAcknowledgementHostValidationImplemented = true", "contract-host-validation")
    need(contract, "const val runtimeDisplayGpuQueueSignalObservationImplemented = true", "contract-runtime-observation")
    need(contract, "const val guestGpuQueueSignalExecuted = false", "contract-execution-false")
    need(contract, "const val guestGpuQueueSignalCompletionObserved = false", "contract-completion-false")
    need(contract, "const val guestGpuQueueSignalPresentOrdered = false", "contract-observed-present-order-false")
    need(contract, "const val guestGpuQueueSignalHostVisibleFrame = false", "contract-visible-frame-false")
    need(contract, "const val synchronizationImplemented = false", "contract-sync-false")

    forbid(vulkan, "pocketpc_guest_vulkan_timeline_probe_first_queue(\n        device,\n        &pocketpc_guest_resource.timeline)", "driver-first-queue-shortcut")
    forbid(contract, "const val guestGpuQueueSignalExecuted = true", "premature-execution-promotion")
    forbid(contract, "const val guestGpuQueueSignalPresentOrdered = true", "premature-present-order-promotion")
    forbid(contract, "const val guestGpuQueueSignalHostVisibleFrame = true", "premature-visible-frame-promotion")
    forbid(contract, "const val synchronizationImplemented = true", "premature-sync-promotion")

    print("GUEST_VULKAN_GPU_QUEUE_POLICY_OK")
    print("queue_signal_primitive_implemented=true")
    print("wine_v49_present_queue_callback_source_integrated=true")
    print("pga_stage5_host_validation_implemented=true")
    print("runtime_result_surface_implemented=true")
    print("queue_signal_executed=false")
    print("same_present_queue_ordering_observed=false")
    print("swapchain_capture=false")
    print("host_visible_frame=false")
    print("gpu_synchronization_executed=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
