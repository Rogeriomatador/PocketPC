#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
HOST = ROOT / "app/src/main/cpp/vulkan_external_image_fd_broker.cpp"
KOTLIN = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/VulkanExternalImageFdBroker.kt"
PVS_H = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_external_timeline_semaphore_fd_protocol.h"
PVS_C = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_external_timeline_semaphore_fd_protocol.c"
IMPORT_H = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_guest_vulkan_timeline_semaphore.h"
IMPORT_C = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_guest_vulkan_timeline_semaphore.c"
MAKEFILE = ROOT / "third_party/wine/pocketpc-driver/Makefile.in"
PREPARER = ROOT / "scripts/prepare-wine-pocketpc-driver.py"
CONTRACT = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GuestGraphicsTransportContract.kt"
RUNNER = ROOT / "scripts/run-pocketpc-external-timeline-semaphore-fd-protocol-smoke.py"


def require(failures: list[str], label: str, text: str, markers: tuple[str, ...]) -> None:
    for marker in markers:
        if marker not in text:
            failures.append(f"{label} missing: {marker}")


def main() -> int:
    failures: list[str] = []
    paths = (HOST, KOTLIN, PVS_H, PVS_C, IMPORT_H, IMPORT_C, MAKEFILE, PREPARER, CONTRACT, RUNNER)
    for path in paths:
        if not path.is_file():
            failures.append(f"missing: {path.relative_to(ROOT)}")
    if failures:
        print("GUEST_VULKAN_TIMELINE_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    host = HOST.read_text(encoding="utf-8")
    kotlin = KOTLIN.read_text(encoding="utf-8")
    pvs_h = PVS_H.read_text(encoding="utf-8")
    pvs_c = PVS_C.read_text(encoding="utf-8")
    import_h = IMPORT_H.read_text(encoding="utf-8")
    import_c = IMPORT_C.read_text(encoding="utf-8")
    makefile = MAKEFILE.read_text(encoding="utf-8")
    preparer = PREPARER.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")
    runner = RUNNER.read_text(encoding="utf-8")

    require(
        failures,
        "host timeline exporter",
        host,
        (
            "VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME",
            "VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME",
            "VkPhysicalDeviceTimelineSemaphoreFeatures",
            "timelineSemaphore == VK_TRUE",
            "VkExportSemaphoreCreateInfo",
            "VkSemaphoreTypeCreateInfo",
            "VK_SEMAPHORE_TYPE_TIMELINE",
            "VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT",
            "vkGetSemaphoreFdKHR",
            "nativeSendTimeline",
            "kExternalTimelineMagic = 0x31535650u",
            "kExternalTimelinePayloadBytes = 40",
        ),
    )
    require(
        failures,
        "Kotlin timeline send",
        kotlin,
        (
            "private external fun nativeSendTimeline(",
            "fun sendTimeline(",
            'const val TIMELINE_PROTOCOL = "PVS1"',
            "const val TIMELINE_INITIAL_VALUE = 0L",
            "TIMELINE_ROLE_FRAME_OWNERSHIP",
        ),
    )
    for forbidden in ("val semaphoreFd:", "val timelineFd:", "ParcelFileDescriptor"):
        if forbidden in kotlin:
            failures.append("Kotlin timeline API exposes process-local fd: " + forbidden)

    require(
        failures,
        "PVS1 header",
        pvs_h,
        (
            "POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_MAGIC 0x31535650u",
            "POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_VERSION 1u",
            "POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_PAYLOAD_BYTES 40u",
            "POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_ROLE_FRAME_OWNERSHIP 1u",
            "pocketpc_external_timeline_semaphore_fd_receive(",
        ),
    )
    require(
        failures,
        "PVS1 receiver",
        pvs_c,
        (
            "SOCK_SEQPACKET",
            "SCM_RIGHTS",
            "MSG_TRUNC",
            "MSG_CTRUNC",
            "pvs_set_cloexec",
            "ownership->state == PGT_STATE_OFFERED_TO_GUEST",
            "metadata->initial_value == 0u",
        ),
    )
    require(
        failures,
        "guest timeline import API",
        import_h,
        (
            "struct pocketpc_guest_vulkan_timeline",
            "pocketpc_guest_vulkan_timeline_import(",
            "pocketpc_guest_vulkan_timeline_release(",
        ),
    )
    require(
        failures,
        "guest timeline import implementation",
        import_c,
        (
            "device->extensions.has_VK_KHR_external_semaphore_fd",
            "VkSemaphoreTypeCreateInfo",
            "VK_SEMAPHORE_TYPE_TIMELINE",
            "VkImportSemaphoreFdInfoKHR",
            "device->p_vkImportSemaphoreFdKHR",
            "received->semaphore_fd = -1;",
        ),
    )
    require(
        failures,
        "Wine build integration",
        makefile,
        (
            "\tpocketpc_external_timeline_semaphore_fd_protocol.c \\",
            "\tpocketpc_guest_vulkan_timeline_semaphore.c \\",
        ),
    )
    require(
        failures,
        "Wine overlay integration",
        preparer,
        (
            '"pocketpc_external_timeline_semaphore_fd_protocol.h",',
            '"pocketpc_external_timeline_semaphore_fd_protocol.c",',
            '"pocketpc_guest_vulkan_timeline_semaphore.h",',
            '"pocketpc_guest_vulkan_timeline_semaphore.c",',
            '"externalTimelineSemaphoreFdProtocol": "PVS1"',
            '"guestVulkanTimelineImportPrimitiveImplemented": True',
        ),
    )
    require(
        failures,
        "runtime contract",
        contract,
        (
            "const val externalTimelineSemaphorePvs1ProtocolImplemented = true",
            "const val hostTimelineSemaphoreExporterImplemented = true",
            "const val guestVulkanTimelineImportPrimitiveImplemented = true",
            "const val synchronizationImplemented = false",
        ),
    )
    require(
        failures,
        "PVS1 smoke runner",
        runner,
        (
            '"protocol": "PVS1"',
            '"timelineSignalWaitExecuted": False',
            '"gpuSynchronizationExecuted": False',
            "SOFTWARE_TEST_PASS",
        ),
    )

    if failures:
        print("GUEST_VULKAN_TIMELINE_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    print("GUEST_VULKAN_TIMELINE_POLICY_OK")
    print("pvs1_protocol_implemented=true")
    print("host_timeline_exporter_implemented=true")
    print("guest_timeline_import_primitive_implemented=true")
    print("timeline_signal_wait_executed=false")
    print("gpu_synchronization_implemented=false")
    print("roblox_executed=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
