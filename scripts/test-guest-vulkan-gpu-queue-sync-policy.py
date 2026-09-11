#!/usr/bin/env python3
"""Static policy for the PocketPC guest Vulkan GPU queue timeline precursor."""
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

    for marker, label in (
        ("pocketpc_guest_vulkan_timeline_signal_queue", "queue-signal-api"),
        ("pocketpc_guest_vulkan_timeline_probe_first_queue", "first-queue-probe-api"),
        ("device->queues[0]", "first-wine-queue-selection"),
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
    need(vulkan, "pocketpc_guest_vulkan_timeline_probe_first_queue", "driver-invokes-queue-probe")
    need(vulkan, "PGA_STAGE_GPU_SIGNAL_SUBMITTED", "driver-emits-stage-five")
    need(vulkan, "present_ordered=0", "driver-non-present-classification")

    need(android_native, "kPgaGpuSignalStage = 5", "android-stage-five-lock")
    need(android_native, "AwaitGpuSignalAck", "android-stage-five-validator")
    need(android_native, "nativeAwaitGpuSignalAck", "android-stage-five-jni")
    need(android_host, "awaitGpuQueueSignalAcknowledgement", "kotlin-stage-five-wrapper")
    need(android_host, "val presentOrdered: Boolean", "kotlin-present-order-separation")
    need(orchestrator, "awaitGpuQueueSignalProbe", "orchestrator-queue-probe-await")
    need(orchestrator, "GPU_QUEUE_SIGNAL_OBSERVED", "orchestrator-explicit-state")
    need(display, "graphicsGpuQueueSignalObserved", "display-observed-result")
    need(display, "graphicsGpuQueueFamilyIndex", "display-queue-family-result")

    # Source availability must never be promoted to executed synchronization.
    need(contract, "const val guestGpuQueueSignalPrimitiveImplemented = true", "contract-source-flag")
    need(contract, "const val guestGpuFirstQueueSignalProbeImplemented = true", "contract-probe-source-flag")
    need(contract, "const val guestGpuQueueSignalAcknowledgementHostValidationImplemented = true", "contract-host-validation")
    need(contract, "const val runtimeDisplayGpuQueueSignalObservationImplemented = true", "contract-runtime-observation")
    need(contract, "const val guestGpuQueueSignalExecuted = false", "contract-execution-false")
    need(contract, "const val guestGpuQueueSignalPresentOrdered = false", "contract-present-order-false")
    need(contract, "const val synchronizationImplemented = false", "contract-sync-false")

    forbid(contract, "const val guestGpuQueueSignalExecuted = true", "premature-execution-promotion")
    forbid(contract, "const val guestGpuQueueSignalPresentOrdered = true", "premature-present-order-promotion")
    forbid(contract, "const val synchronizationImplemented = true", "premature-sync-promotion")

    print("GUEST_VULKAN_GPU_QUEUE_POLICY_OK")
    print("queue_signal_primitive_implemented=true")
    print("first_wine_queue_probe_implemented=true")
    print("pga_stage5_host_validation_implemented=true")
    print("runtime_result_surface_implemented=true")
    print("queue_signal_executed=false")
    print("present_ordering_proven=false")
    print("gpu_synchronization_executed=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
