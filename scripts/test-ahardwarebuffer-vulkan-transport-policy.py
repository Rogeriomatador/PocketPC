#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
ARCH = ROOT / "third_party/wine/POCKETPC_VULKAN_WSI_ARCHITECTURE.json"
NATIVE = ROOT / "app/src/main/cpp/runtime_host.cpp"
CROSS_PROCESS_NATIVE = ROOT / "app/src/main/cpp/hardware_buffer_cross_process.cpp"
CMAKE = ROOT / "app/src/main/cpp/CMakeLists.txt"
HOST = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/NativeRuntimeHost.kt"
WSI = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/PocketPcVulkanWsiContract.kt"
FOUNDATION = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/PocketPcVulkanWsiFoundation.kt"
GUEST = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GuestGraphicsTransportContract.kt"
DESCRIPTOR = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GuestGraphicsResourceDescriptor.kt"
BOX64 = ROOT / "third_party/box64/LOCK.json"


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
        arch = json.loads(ARCH.read_text(encoding="utf-8"))
        box64 = json.loads(BOX64.read_text(encoding="utf-8"))
    except Exception as error:
        print("AHARDWAREBUFFER_VULKAN_TRANSPORT_POLICY_FAILED", file=sys.stderr)
        print(f"- json: {error}", file=sys.stderr)
        return 1

    if arch.get("schemaVersion") != 4:
        failures.append("architecture schema is not v4")
    if arch.get("status") != (
        "VULKAN_ABI_FAIL_CLOSED_IMPLEMENTED_GUEST_TRANSPORT_NOT_IMPLEMENTED_"
        "WSI_NOT_IMPLEMENTED"
    ):
        failures.append("architecture status changed")

    wine = arch.get("wine") or {}
    if (
        wine.get("version") != "11.0"
        or wine.get("commit") != "db11d0fe6a169c457e23d007e20404643d067aa8"
        or wine.get("vulkanDriverVersion") != 47
        or wine.get("requiredEntryPoint") != "user_driver_funcs.pVulkanInit"
        or wine.get("abiEntryPointImplemented") is not True
        or wine.get("surfaceCreateImplemented") is not False
    ):
        failures.append("Wine Vulkan ABI/fail-closed contract changed")

    if box64.get("version") != "0.4.4" or box64.get("commit") != (
        "2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a"
    ):
        failures.append("Box64 source lock changed")

    gates = arch.get("gates") or {}
    expected_true = (
        "box64AndroidVulkanWrapperSourceReviewed",
        "ahardwareBufferHostProbeImplemented",
        "ahardwareBufferCrossProcessProbeImplemented",
        "ahardwareBufferEvidenceProtocolImplemented",
        "vulkanCapabilityProbeImplemented",
        "wineVulkanAbiEntryPointImplemented",
        "guestGraphicsTransportContractImplemented",
    )
    expected_false = (
        "ahardwareBufferHostProbeSoftwareTestExecuted",
        "ahardwareBufferHostProbePhysicalTestExecuted",
        "ahardwareBufferCrossProcessPhysicalTestExecuted",
        "vulkanCapabilityProbeSoftwareTestExecuted",
        "vulkanCapabilityProbePhysicalTestExecuted",
        "guestHardwareBufferReceiveImplemented",
        "guestGraphicsResourceImportImplemented",
        "guestGraphicsSynchronizationImplemented",
        "guestGraphicsTransportSoftwareTestExecuted",
        "guestGraphicsTransportIntegrationTestExecuted",
        "guestGraphicsTransportPhysicalTestExecuted",
        "wineVulkanWsiImplemented",
        "wineVulkanWsiSoftwareTestExecuted",
        "wineVulkanWsiPhysicalTestExecuted",
        "d3d11PresentHostVisibleFrameExecuted",
        "controlledDxvkApplicationAttemptAllowed",
        "robloxControlledAttemptExecuted",
        "robloxGameplayValidated",
    )

    for key in expected_true:
        if gates.get(key) is not True:
            failures.append(f"expected true gate: {key}")
    for key in expected_false:
        if gates.get(key) is not False:
            failures.append(f"expected false gate: {key}")

    guest_arch = arch.get("guestGraphicsTransport") or {}
    if (
        guest_arch.get("protocolVersion") != 1
        or guest_arch.get("descriptorProtocolImplemented") is not True
        or guest_arch.get("guestReceiveImplemented") is not False
        or guest_arch.get("guestImportImplemented") is not False
        or guest_arch.get("synchronizationImplemented") is not False
    ):
        failures.append("guest graphics transport architecture state changed")

    native = NATIVE.read_text(encoding="utf-8")
    cross_native = CROSS_PROCESS_NATIVE.read_text(encoding="utf-8")
    cmake = CMAKE.read_text(encoding="utf-8")
    host = HOST.read_text(encoding="utf-8")
    wsi = WSI.read_text(encoding="utf-8")
    foundation = FOUNDATION.read_text(encoding="utf-8")
    guest = GUEST.read_text(encoding="utf-8")
    descriptor = DESCRIPTOR.read_text(encoding="utf-8")

    require(
        failures,
        "native AHardwareBuffer probe",
        native,
        (
            "#include <android/hardware_buffer.h>",
            "AHardwareBuffer_allocate",
            "AHardwareBuffer_describe",
            "AHardwareBuffer_lock",
            "AHardwareBuffer_unlock",
            "AHardwareBuffer_sendHandleToUnixSocket",
            "AHardwareBuffer_recvHandleFromUnixSocket",
            "socketpair(",
            "AF_UNIX",
            "cross_process_transport=",
            "vulkan_wsi=not-tested",
        ),
    )
    require(
        failures,
        "cross-process AHardwareBuffer probe",
        cross_native,
        (
            "AHardwareBuffer_sendHandleToUnixSocket",
            "AHardwareBuffer_recvHandleFromUnixSocket",
            "protocol=",
            "pattern_match=",
            "descriptor_match=",
        ),
    )
    require(
        failures,
        "native host linkage",
        cmake,
        (
            "hardware_buffer_cross_process.cpp",
            "find_library(android_lib android)",
            "${android_lib}",
            "${vulkan_lib}",
        ),
    )
    require(
        failures,
        "Kotlin host bridge",
        host,
        (
            "hardwareBufferProbe",
            "nativeHardwareBufferProbe",
            "ahardwarebuffer=not-probed",
        ),
    )
    require(
        failures,
        "guest graphics fail-closed contract",
        guest,
        (
            "object GuestGraphicsTransportContract",
            "const val descriptorProtocolImplemented =\n        true",
            "const val guestReceiveImplemented =\n        false",
            "const val guestImportImplemented =\n        false",
            "const val synchronizationImplemented =\n        false",
            '"VULKAN_WSI_GUEST_GRAPHICS_TRANSPORT_NOT_IMPLEMENTED"',
        ),
    )
    require(
        failures,
        "guest graphics descriptor",
        descriptor,
        (
            "object GuestGraphicsResourceDescriptorCodec",
            "const val CURRENT_PROTOCOL = 1",
            '"resource_id"',
            '"generation"',
            '"producer_pid"',
            '"process_namespace"',
            '"sync_sequence"',
            "fields.keys != REQUIRED_FIELDS",
        ),
    )
    for forbidden in (
        '"raw_pointer"',
        '"native_window"',
        '"fd"',
    ):
        if forbidden in descriptor:
            failures.append(
                "descriptor must not define process-local transport field: " + forbidden
            )

    require(
        failures,
        "WSI fail-closed contract",
        wsi,
        (
            "const val implemented =\n        false",
            '"VULKAN_WSI_NOT_IMPLEMENTED"',
            '"guest-graphics-handle-receive"',
            '"guest-graphics-resource-import"',
            '"guest-graphics-synchronization"',
            "foundation.guestGraphicsTransportReady",
        ),
    )
    require(
        failures,
        "WSI foundation guest boundary",
        foundation,
        (
            "GuestGraphicsTransportContract.readyForWsiImplementation()",
            "BLOCKER_GUEST_GRAPHICS_TRANSPORT",
            "guestGraphicsTransportReady",
        ),
    )

    rejected = arch.get("rejectedRoutes") or []
    rejected_text = json.dumps(rejected, sort_keys=True)
    for marker in (
        "ANativeWindow",
        "GDI window_surface.flush",
        "wineandroid.drv",
        "Android-to-Android AHardwareBuffer",
    ):
        if marker not in rejected_text:
            failures.append(f"rejected route missing: {marker}")

    if failures:
        print("AHARDWAREBUFFER_VULKAN_TRANSPORT_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    print("AHARDWAREBUFFER_VULKAN_TRANSPORT_POLICY_OK")
    print("android_cross_process_probe_implemented=true")
    print("guest_descriptor_protocol_implemented=true")
    print("guest_receive_implemented=false")
    print("guest_import_implemented=false")
    print("guest_synchronization_implemented=false")
    print("wine_vulkan_wsi_implemented=false")
    print("physical_execution_evidence=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
