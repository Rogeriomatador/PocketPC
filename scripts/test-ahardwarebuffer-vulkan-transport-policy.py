#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
ARCH = ROOT / "third_party/wine/POCKETPC_VULKAN_WSI_ARCHITECTURE.json"
BOX64 = ROOT / "third_party/box64/LOCK.json"
CONTRACT = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GuestGraphicsTransportContract.kt"
WSI = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/PocketPcVulkanWsiContract.kt"
FOUNDATION = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/PocketPcVulkanWsiFoundation.kt"
AHB = ROOT / "app/src/main/cpp/hardware_buffer_cross_process.cpp"
AHB_CANONICAL = ROOT / "app/src/main/cpp/vulkan_ahardwarebuffer_import_probe.cpp"
IMAGE_HOST = ROOT / "app/src/main/cpp/vulkan_external_image_fd_broker.cpp"
PVI = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_external_image_fd_protocol.c"
PVS = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_external_timeline_semaphore_fd_protocol.c"
IMAGE_IMPORT = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_guest_vulkan_import.c"
TIMELINE_IMPORT = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_guest_vulkan_timeline_semaphore.c"


def require(failures: list[str], label: str, text: str, markers: tuple[str, ...]) -> None:
    for marker in markers:
        if marker not in text:
            failures.append(f"{label} missing: {marker}")


def main() -> int:
    failures: list[str] = []
    try:
        arch = json.loads(ARCH.read_text(encoding="utf-8"))
        box64 = json.loads(BOX64.read_text(encoding="utf-8"))
    except Exception as error:
        print("AHARDWAREBUFFER_VULKAN_TRANSPORT_POLICY_FAILED", file=sys.stderr)
        print(f"- json: {error}", file=sys.stderr)
        return 1

    if arch.get("schemaVersion") != 8:
        failures.append("architecture schema must be v8")
    if arch.get("status") != "VULKAN_TRANSPORT_PRIMITIVES_IMPLEMENTED_VISIBLE_WSI_NOT_IMPLEMENTED_NOT_EXECUTED":
        failures.append("architecture status changed without policy update")

    if box64.get("version") != "0.4.4" or box64.get("commit") != "2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a":
        failures.append("Box64 source lock changed")

    wine = arch.get("wine") or {}
    for key in (
        "abiEntryPointImplemented",
        "externalHandleFdMappingImplemented",
        "headlessDiagnosticSurfaceImplemented",
        "headlessPresentObserverImplemented",
    ):
        if wine.get(key) is not True:
            failures.append(f"Wine implemented gate missing: {key}")
    for key in (
        "visibleSurfaceCreateImplemented",
        "visiblePresentationSupportImplemented",
        "visibleSurfaceExtensionMappingImplemented",
        "swapchainCaptureImplemented",
        "hostVisiblePresentImplemented",
        "softwareTestExecuted",
        "integrationTestExecuted",
        "physicalTestExecuted",
    ):
        if wine.get(key) is not False:
            failures.append(f"Wine gate must remain false: {key}")

    host = arch.get("hostGraphics") or {}
    for key in (
        "ahardwareBufferCrossProcessProbeImplemented",
        "canonicalAhardwareBufferImportPropertyProbeImplemented",
        "externalResourceCapabilityProbeImplemented",
        "opaqueFdImageBrokerImplemented",
        "opaqueFdTimelineSemaphoreExporterImplemented",
        "pvi1ImageProtocolImplemented",
        "pvs1TimelineProtocolImplemented",
        "imageAndTimelineShareSameVkDevice",
    ):
        if host.get(key) is not True:
            failures.append(f"host graphics foundation missing: {key}")
    for key in ("softwareTestExecuted", "integrationTestExecuted", "physicalTestExecuted"):
        if host.get(key) is not False:
            failures.append(f"host execution gate must remain false: {key}")

    guest = arch.get("guestGraphics") or {}
    for key in (
        "descriptorProtocolImplemented",
        "ownershipProtocolImplemented",
        "ancillaryFdTransportPrimitiveImplemented",
        "handleBindingImplemented",
        "receivePrimitiveImplemented",
        "pvi1ReceiverImplemented",
        "opaqueFdVulkanImageImportPrimitiveImplemented",
        "pvs1ReceiverImplemented",
        "timelineSemaphoreImportPrimitiveImplemented",
        "timelineCounterPrimitiveImplemented",
        "timelineCpuSignalPrimitiveImplemented",
        "timelineCpuWaitPrimitiveImplemented",
    ):
        if guest.get(key) is not True:
            failures.append(f"guest primitive missing: {key}")
    for key in (
        "authenticatedRuntimeReceiveIntegrated",
        "activeWineDeviceImageImportIntegrated",
        "timelineCpuRoundTripExecuted",
        "gpuQueueSynchronizationImplemented",
        "gpuQueueSynchronizationExecuted",
        "softwareTestExecuted",
        "integrationTestExecuted",
        "physicalTestExecuted",
    ):
        if guest.get(key) is not False:
            failures.append(f"guest integration/execution gate must remain false: {key}")

    gates = arch.get("gates") or {}
    for key in (
        "authenticatedGuestGraphicsReceiveIntegrated",
        "guestVulkanImageImportIntegrated",
        "timelineCpuRoundTripExecuted",
        "guestGraphicsGpuSynchronizationImplemented",
        "guestGraphicsGpuSynchronizationExecuted",
        "swapchainImageCaptureHookImplemented",
        "hostVisiblePresentImplemented",
        "visibleVulkanSurfaceBackendRunnable",
        "wineVisibleVulkanWsiImplemented",
        "d3d11PresentHostVisibleFrameExecuted",
        "robloxControlledAttemptExecuted",
        "robloxGameplayValidated",
        "softwareTestExecutedForCurrentRevision",
        "integrationTestExecutedForCurrentRevision",
        "physicalTestExecutedForCurrentRevision",
    ):
        if gates.get(key) is not False:
            failures.append(f"fail-closed gate must remain false: {key}")

    texts = {
        "contract": CONTRACT.read_text(encoding="utf-8"),
        "wsi": WSI.read_text(encoding="utf-8"),
        "foundation": FOUNDATION.read_text(encoding="utf-8"),
        "ahb": AHB.read_text(encoding="utf-8"),
        "canonical ahb": AHB_CANONICAL.read_text(encoding="utf-8"),
        "host image": IMAGE_HOST.read_text(encoding="utf-8"),
        "pvi1": PVI.read_text(encoding="utf-8"),
        "pvs1": PVS.read_text(encoding="utf-8"),
        "image import": IMAGE_IMPORT.read_text(encoding="utf-8"),
        "timeline import": TIMELINE_IMPORT.read_text(encoding="utf-8"),
    }

    require(failures, "runtime contract", texts["contract"], (
        "const val externalImagePvi1ProtocolImplemented = true",
        "const val guestVulkanImportPrimitiveImplemented = true",
        "const val externalTimelineSemaphorePvs1ProtocolImplemented = true",
        "const val hostTimelineSemaphoreExporterImplemented = true",
        "const val guestVulkanTimelineImportPrimitiveImplemented = true",
        "const val guestReceiveImplemented = false",
        "const val guestImportImplemented = false",
        "const val synchronizationImplemented = false",
    ))
    require(failures, "WSI contract", texts["wsi"], (
        "const val implemented =",
        "false",
        "VULKAN_WSI_NOT_IMPLEMENTED",
    ))
    require(failures, "WSI foundation", texts["foundation"], (
        "GuestGraphicsTransportContract.readyForWsiImplementation()",
        "BLOCKER_GUEST_GRAPHICS_TRANSPORT",
    ))
    require(failures, "cross process AHB", texts["ahb"], (
        "AHardwareBuffer_sendHandleToUnixSocket",
        "AHardwareBuffer_recvHandleFromUnixSocket",
        "descriptor_match=",
        "pattern_match=",
    ))
    require(failures, "canonical AHB", texts["canonical ahb"], (
        "vkGetAndroidHardwareBufferPropertiesANDROID",
        "allocationSize",
        "memoryTypeBits",
        "canonical_import_query_supported",
    ))
    require(failures, "host image and timeline", texts["host image"], (
        "VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME",
        "VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME",
        "VK_SEMAPHORE_TYPE_TIMELINE",
        "nativeSendTimeline",
        "kExternalImageMagic = 0x31495650u",
        "kExternalTimelineMagic = 0x31535650u",
    ))
    require(failures, "PVI1", texts["pvi1"], (
        "SCM_RIGHTS",
        "SOCK_SEQPACKET",
        "pocketpc_graphics_handle_binding_validate_offer",
    ))
    require(failures, "PVS1", texts["pvs1"], (
        "SCM_RIGHTS",
        "SOCK_SEQPACKET",
        "metadata->initial_value == 0u",
        "PGT_STATE_OFFERED_TO_GUEST",
    ))
    require(failures, "guest image import", texts["image import"], (
        "device->p_vkGetMemoryFdPropertiesKHR",
        "VkImportMemoryFdInfoKHR",
        "device->physical_device",
        "received->resource_fd = -1;",
    ))
    require(failures, "guest timeline import", texts["timeline import"], (
        "VkImportSemaphoreFdInfoKHR",
        "device->p_vkImportSemaphoreFdKHR",
        "pocketpc_guest_vulkan_timeline_signal_cpu",
        "pocketpc_guest_vulkan_timeline_wait_cpu",
        "pocketpc_guest_vulkan_timeline_get_counter",
    ))

    rejected = json.dumps(arch.get("rejectedRoutes") or [], sort_keys=True)
    for marker in (
        "ANativeWindow",
        "AHardwareBuffer as VkSurfaceKHR",
        "memoryTypeIndex",
        "CPU timeline signal/wait",
        "headless Present observer",
        "Roblox readiness",
    ):
        if marker not in rejected:
            failures.append(f"rejected-route guard missing: {marker}")

    if failures:
        print("AHARDWAREBUFFER_VULKAN_TRANSPORT_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    print("AHARDWAREBUFFER_VULKAN_TRANSPORT_POLICY_OK")
    print("architecture_schema=8")
    print("pvi1_implemented=true")
    print("pvs1_implemented=true")
    print("guest_vulkan_import_primitive=true")
    print("guest_timeline_cpu_primitives=true")
    print("authenticated_runtime_receive=false")
    print("gpu_queue_synchronization=false")
    print("host_visible_present=false")
    print("roblox_executed=false")
    print("current_revision_execution=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
