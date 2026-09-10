#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
BOX64_LOCK = ROOT / "third_party/box64/LOCK.json"
BOX64_GRAPHICS = ROOT / "third_party/box64/ANDROID_GRAPHICS_BRIDGE.json"
CONTRACT = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GuestGraphicsTransportContract.kt"
PLANNER = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GuestGraphicsTransportPlanner.kt"
BROKER_KT = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/HostGraphicsResourceBroker.kt"
BROKER_NATIVE = ROOT / "app/src/main/cpp/hardware_buffer_resource_broker.cpp"
EXTERNAL_PROBE_KT = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/VulkanExternalResourceProbe.kt"
EXTERNAL_PROBE_NATIVE = ROOT / "app/src/main/cpp/vulkan_external_resource_probe.cpp"
CMAKE = ROOT / "app/src/main/cpp/CMakeLists.txt"


def require(
    failures: list[str],
    label: str,
    text: str,
    markers: tuple[str, ...],
) -> None:
    for marker in markers:
        if marker not in text:
            failures.append(f"{label} missing: {marker}")


def main() -> int:
    failures: list[str] = []

    try:
        box64_lock = json.loads(BOX64_LOCK.read_text(encoding="utf-8"))
        box64_graphics = json.loads(BOX64_GRAPHICS.read_text(encoding="utf-8"))
    except Exception as error:
        print("GUEST_GRAPHICS_HOST_BROKER_POLICY_FAILED", file=sys.stderr)
        print(f"- json: {error}", file=sys.stderr)
        return 1

    expected_box64_commit = "2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a"
    if (
        box64_lock.get("version") != "0.4.4"
        or box64_lock.get("commit") != expected_box64_commit
    ):
        failures.append("Box64 source lock changed")

    if box64_graphics.get("schemaVersion") != 1:
        failures.append("Box64 graphics audit schema changed")
    if box64_graphics.get("status") != (
        "STATICALLY_INSPECTED_PARTIAL_NOT_RUNTIME_TESTED"
    ):
        failures.append("Box64 graphics audit status must remain non-runtime-tested")

    box64 = box64_graphics.get("box64") or {}
    if (
        box64.get("version") != "0.4.4"
        or box64.get("commit") != expected_box64_commit
    ):
        failures.append("Box64 graphics audit does not match source lock")

    source_evidence = box64_graphics.get("sourceEvidence") or {}
    if source_evidence.get("wrappedAndroidShmemPresent") is not True:
        failures.append("wrappedandroidshmem source evidence missing")
    if source_evidence.get("wrappedAndroidSupportPresent") is not True:
        failures.append("wrappedandroidsupport source evidence missing")
    for key in (
        "directLibandroidWrapperFileVerified",
        "directAhardwareBufferWrapperVerified",
    ):
        if source_evidence.get(key) is not False:
            failures.append(f"unverified Box64 direct bridge promoted: {key}")

    guest_gates = box64_graphics.get("guestGates") or {}
    for key in (
        "directAhardwareBufferBridgeVerified",
        "handleReceiveImplemented",
        "vulkanResourceImportImplemented",
        "synchronizationImplemented",
        "runtimeTestExecuted",
        "physicalTestExecuted",
    ):
        if guest_gates.get(key) is not False:
            failures.append(f"guest graphics gate must remain false: {key}")

    contract = CONTRACT.read_text(encoding="utf-8")
    planner = PLANNER.read_text(encoding="utf-8")
    broker_kt = BROKER_KT.read_text(encoding="utf-8")
    broker_native = BROKER_NATIVE.read_text(encoding="utf-8")
    external_probe_kt = EXTERNAL_PROBE_KT.read_text(encoding="utf-8")
    external_probe_native = EXTERNAL_PROBE_NATIVE.read_text(encoding="utf-8")
    cmake = CMAKE.read_text(encoding="utf-8")

    require(
        failures,
        "guest transport contract",
        contract,
        (
            "const val hostAhardwareBufferBrokerImplemented =\n        true",
            "const val externalResourceCapabilityProbeImplemented =\n        true",
            "const val box64DirectAhardwareBufferBridgeVerified =\n        false",
            "const val guestReceiveImplemented =\n        false",
            "const val guestImportImplemented =\n        false",
            "const val synchronizationImplemented =\n        false",
        ),
    )

    require(
        failures,
        "native host AHardwareBuffer broker",
        broker_native,
        (
            "std::unordered_map<uint64_t, ResourceEntry>",
            "std::mutex g_registry_mutex",
            "AHardwareBuffer_allocate",
            "AHardwareBuffer_acquire",
            "AHardwareBuffer_sendHandleToUnixSocket",
            "AHardwareBuffer_release",
            "stale-or-unknown-resource",
            "generation",
        ),
    )
    for forbidden in (
        "reinterpret_cast<uint64_t>(buffer)",
        "reinterpret_cast<long>(buffer)",
        "raw_pointer=",
        "native_window=",
    ):
        if forbidden in broker_native:
            failures.append(
                "native broker serializes process-local identity: " + forbidden
            )

    require(
        failures,
        "Kotlin host broker",
        broker_kt,
        (
            "data class HostGraphicsResourceLease",
            "fun toGuestDescriptor(",
            "GuestGraphicsResourceDescriptor(",
            "nativeCreateBuffer",
            "nativeSendBuffer",
            "nativeReleaseBuffer",
            "HostGraphicsBrokerRecordParser",
        ),
    )

    require(
        failures,
        "Vulkan external resource native probe",
        external_probe_native,
        (
            "VK_KHR_external_memory_capabilities",
            "VK_KHR_external_memory_fd",
            "VK_EXT_external_memory_dma_buf",
            "VK_ANDROID_external_memory_android_hardware_buffer",
            "VK_KHR_external_semaphore_fd",
            "VK_KHR_external_fence_fd",
            "vkGetPhysicalDeviceExternalBufferPropertiesKHR",
            "VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT",
            "VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT",
        ),
    )
    require(
        failures,
        "Vulkan external resource Kotlin probe",
        external_probe_kt,
        (
            "opaqueFdMemoryRouteAdvertised",
            "dmaBufMemoryRouteAdvertised",
            "ahardwareBufferMemoryRouteAdvertised",
            "fdSynchronizationRouteAdvertised",
            "potentialGuestFdTransportRoute",
            "GuestGraphicsTransportContract" if False else "PROTOCOL_VERSION = 1",
        ),
    )

    require(
        failures,
        "guest graphics route planner",
        planner,
        (
            "GuestGraphicsTransportCandidate.OPAQUE_FD",
            "GuestGraphicsTransportCandidate.DMA_BUF_FD",
            "GuestGraphicsTransportCandidate.AHB_HOST_BROKER_ONLY",
            "BOX64_AHARDWAREBUFFER_BRIDGE_NOT_VERIFIED",
            "GUEST_GRAPHICS_HANDLE_RECEIVE_NOT_IMPLEMENTED",
            "GUEST_GRAPHICS_RESOURCE_IMPORT_NOT_IMPLEMENTED",
            "GUEST_GRAPHICS_SYNCHRONIZATION_NOT_IMPLEMENTED",
        ),
    )

    require(
        failures,
        "native build integration",
        cmake,
        (
            "hardware_buffer_resource_broker.cpp",
            "vulkan_external_resource_probe.cpp",
            "find_library(vulkan_lib vulkan)",
            "find_library(android_lib android)",
        ),
    )

    rules_text = json.dumps(box64_graphics.get("rules") or [])
    for marker in (
        "VkSurfaceKHR",
        "raw pointer",
        "Host broker implementation does not promote",
        "Roblox",
    ):
        if marker not in rules_text:
            failures.append(f"Box64 graphics rule missing: {marker}")

    if failures:
        print("GUEST_GRAPHICS_HOST_BROKER_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    print("GUEST_GRAPHICS_HOST_BROKER_POLICY_OK")
    print("host_ahardwarebuffer_broker_implemented=true")
    print("external_resource_capability_probe_implemented=true")
    print("box64_direct_ahardwarebuffer_bridge_verified=false")
    print("guest_receive_implemented=false")
    print("guest_import_implemented=false")
    print("guest_synchronization_implemented=false")
    print("runtime_test_executed=false")
    print("physical_test_executed=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
