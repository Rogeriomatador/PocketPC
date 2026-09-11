#!/usr/bin/env python3
"""Static policy for the PocketPC guest Vulkan GPU queue timeline precursor."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HEADER = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_guest_vulkan_timeline_semaphore.h"
SOURCE = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_guest_vulkan_timeline_semaphore.c"
ACK_H = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_graphics_ack.h"
ACK_C = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_graphics_ack.c"
CONTRACT = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GuestGraphicsTransportContract.kt"


def need(text: str, marker: str, label: str) -> None:
    if marker not in text:
        raise SystemExit(f"GUEST_VULKAN_GPU_QUEUE_POLICY_MISSING:{label}")


def main() -> int:
    header = HEADER.read_text(encoding="utf-8")
    source = SOURCE.read_text(encoding="utf-8")
    ack_h = ACK_H.read_text(encoding="utf-8")
    ack_c = ACK_C.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")

    for marker, label in (
        ("pocketpc_guest_vulkan_timeline_signal_queue", "queue-signal-api"),
        ("struct vulkan_queue *queue", "wine-queue-identity"),
        ("VkTimelineSemaphoreSubmitInfo", "timeline-submit-info"),
        ("VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO", "timeline-submit-type"),
        ("device->p_vkQueueSubmit", "real-vulkan-queue-submit"),
        ("queue->host.queue", "host-queue-handle"),
        ("signalSemaphoreCount = 1u", "single-signal"),
        ("VK_NULL_HANDLE", "no-fence-shortcut"),
    ):
        need(header + "\n" + source, marker, label)

    need(ack_h, "PGA_STAGE_GPU_SIGNAL_SUBMITTED 5u", "gpu-submit-ack-stage")
    need(ack_c, "PGA_STAGE_GPU_SIGNAL_SUBMITTED", "gpu-submit-ack-accepted")

    # Source availability must never be promoted to executed synchronization.
    need(contract, "const val guestGpuQueueSignalPrimitiveImplemented", "contract-source-flag")
    need(contract, "const val guestGpuQueueSignalExecuted = false", "contract-execution-false")
    need(contract, "const val synchronizationImplemented = false", "contract-sync-false")

    print("GUEST_VULKAN_GPU_QUEUE_POLICY_OK")
    print("queue_signal_primitive_implemented=true")
    print("queue_signal_executed=false")
    print("present_ordering_proven=false")
    print("gpu_synchronization_executed=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
