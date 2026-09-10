#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
ARCH = ROOT / "third_party/wine/POCKETPC_VULKAN_WSI_ARCHITECTURE.json"
NATIVE = ROOT / "app/src/main/cpp/runtime_host.cpp"
CROSS_PROCESS_NATIVE = ROOT / "app/src/main/cpp/hardware_buffer_cross_process.cpp"
BROKER_NATIVE = ROOT / "app/src/main/cpp/hardware_buffer_resource_broker.cpp"
AHB_IMPORT_NATIVE = ROOT / "app/src/main/cpp/vulkan_ahardwarebuffer_import_probe.cpp"
CMAKE = ROOT / "app/src/main/cpp/CMakeLists.txt"
HOST = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/NativeRuntimeHost.kt"
AHB_IMPORT_KT = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/VulkanAhardwareBufferImportProbe.kt"
WSI = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/PocketPcVulkanWsiContract.kt"
FOUNDATION = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/PocketPcVulkanWsiFoundation.kt"
GUEST = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GuestGraphicsTransportContract.kt"
DESCRIPTOR = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GuestGraphicsResourceDescriptor.kt"
SURFACE_BACKEND = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/PocketPcVulkanSurfaceBackend.kt"
BOX64 = ROOT / "third_party/box64/LOCK.json"
GUEST_RECEIVE = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_guest_graphics_receive.c"
FD_TRANSPORT = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_fd_transport.c"
HANDLE_BINDING = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_graphics_handle_binding.c"


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

    if arch.get("schemaVersion") != 5:
        failures.append("architecture schema is not v5")
    if arch.get("status") != (
        "VULKAN_ABI_HEADLESS_DIAGNOSTIC_IMPLEMENTED_VISIBLE_WSI_NOT_IMPLEMENTED"
    ):
        failures.append("architecture status changed")

    wine = arch.get("wine") or {}
    if (
        wine.get("version") != "11.0"
        or wine.get("commit") != "db11d0fe6a169c457e23d007e20404643d067aa8"
        or wine.get("vulkanDriverVersion") != 47
        or wine.get("requiredEntryPoint") != "user_driver_funcs.pVulkanInit"
        or wine.get("abiEntryPointImplemented") is not True
        or wine.get("externalHandleFdMappingImplemented") is not True
        or wine.get("headlessDiagnosticSurfaceImplemented") is not True
        or wine.get("surfaceCreateImplemented") is not False
        or wine.get("presentationSupportImplemented") is not False
    ):
        failures.append("Wine Vulkan ABI/diagnostic/visible fail-closed contract changed")

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
        "ahardwareBufferResourceBrokerImplemented",
        "canonicalAhardwareBufferImportProbeImplemented",
        "vulkanCapabilityProbeImplemented",
        "vulkanExternalResourceProbeImplemented",
        "wineVulkanAbiEntryPointImplemented",
        "wineExternalHandleFdMappingImplemented",
        "wineHeadlessDiagnosticSurfaceImplemented",
        "guestGraphicsTransportContractImplemented",
        "guestGraphicsAncillaryFdTransportPrimitiveImplemented",
        "guestGraphicsHandleBindingImplemented",
        "guestGraphicsReceivePrimitiveImplemented",
        "vulkanSurfaceBackendModelImplemented",
    )
    expected_false = (
        "ahardwareBufferHostProbeSoftwareTestExecuted",
        "ahardwareBufferHostProbePhysicalTestExecuted",
        "ahardwareBufferCrossProcessPhysicalTestExecuted",
        "canonicalAhardwareBufferImportProbePhysicalTestExecuted",
        "vulkanCapabilityProbeSoftwareTestExecuted",
        "vulkanCapabilityProbePhysicalTestExecuted",
        "wineExternalHandleFdMappingSoftwareTestExecuted",
        "wineHeadlessDiagnosticSoftwareTestExecuted",
        "wineHeadlessDiagnosticIntegrationTestExecuted",
        "guestGraphicsReceiveRuntimeIntegrated",
        "guestGraphicsResourceImportImplemented",
        "guestGraphicsSynchronizationImplemented",
        "guestGraphicsTransportSoftwareTestExecuted",
        "guestGraphicsTransportIntegrationTestExecuted",
        "guestGraphicsTransportPhysicalTestExecuted",
        "vulkanVisibleSurfaceBackendRunnable",
        "wineVisibleVulkanWsiImplemented",
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
        or guest_arch.get("ownershipProtocolImplemented") is not True
        or guest_arch.get("ancillaryFdTransportPrimitiveImplemented") is not True
        or guest_arch.get("handleBindingImplemented") is not True
        or guest_arch.get("guestReceivePrimitiveImplemented") is not True
        or guest_arch.get("guestReceiveRuntimeIntegrated") is not False
        or guest_arch.get("guestImportImplemented") is not False
        or guest_arch.get("synchronizationImplemented") is not False
    ):
        failures.append("guest graphics transport architecture state changed")

    surface_arch = arch.get("surfaceBackend") or {}
    surface_candidates = surface_arch.get("candidates") or {}
    if (
        surface_arch.get("model") != "PocketPcVulkanSurfaceBackendProbe"
        or surface_arch.get("modelImplemented") is not True
        or surface_arch.get("productionRunnable") is not False
        or surface_arch.get("ahardwareBufferIsSurface") is not False
    ):
        failures.append("surface backend architecture state changed")

    if (surface_candidates.get("HEADLESS_DIAGNOSTIC") or {}).get("implemented") is not True:
        failures.append("headless diagnostic backend must be represented as implemented code")
    for candidate_name in (
        "ANDROID_NATIVE_SURFACE",
        "HEADLESS_SURFACE_SHIM_VISIBLE",
        "VIRTUAL_WSI",
    ):
        candidate = surface_candidates.get(candidate_name) or {}
        if candidate.get("implemented") is not False:
            failures.append(f"visible surface backend must remain unimplemented: {candidate_name}")

    native = NATIVE.read_text(encoding="utf-8")
    cross_native = CROSS_PROCESS_NATIVE.read_text(encoding="utf-8")
    broker_native = BROKER_NATIVE.read_text(encoding="utf-8")
    ahb_import_native = AHB_IMPORT_NATIVE.read_text(encoding="utf-8")
    cmake = CMAKE.read_text(encoding="utf-8")
    host = HOST.read_text(encoding="utf-8")
    ahb_import_kt = AHB_IMPORT_KT.read_text(encoding="utf-8")
    wsi = WSI.read_text(encoding="utf-8")
    foundation = FOUNDATION.read_text(encoding="utf-8")
    guest = GUEST.read_text(encoding="utf-8")
    descriptor = DESCRIPTOR.read_text(encoding="utf-8")
    surface_backend = SURFACE_BACKEND.read_text(encoding="utf-8")
    guest_receive = GUEST_RECEIVE.read_text(encoding="utf-8")
    fd_transport = FD_TRANSPORT.read_text(encoding="utf-8")
    handle_binding = HANDLE_BINDING.read_text(encoding="utf-8")

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
        "host AHardwareBuffer resource broker",
        broker_native,
        (
            "AHardwareBuffer_allocate",
            "AHardwareBuffer_acquire",
            "AHardwareBuffer_sendHandleToUnixSocket",
            "AHardwareBuffer_release",
            "generation",
            "stale-or-unknown-resource",
        ),
    )
    require(
        failures,
        "canonical AHardwareBuffer Vulkan query",
        ahb_import_native,
        (
            "vkGetAndroidHardwareBufferPropertiesANDROID",
            "VK_ANDROID_external_memory_android_hardware_buffer",
            "VK_EXT_queue_family_foreign",
            "allocationSize",
            "memoryTypeBits",
            "canonical_import_query_supported",
        ),
    )
    require(
        failures,
        "canonical AHardwareBuffer Kotlin parser",
        ahb_import_kt,
        (
            "canonicalImportQuerySupported",
            "propertiesQuerySucceeded",
            "allocationSizeNonzero",
            "memoryTypeBitsNonzero",
            "nativeCanonicalImportClaim",
        ),
    )
    require(
        failures,
        "native host linkage",
        cmake,
        (
            "hardware_buffer_cross_process.cpp",
            "hardware_buffer_resource_broker.cpp",
            "vulkan_external_resource_probe.cpp",
            "vulkan_ahardwarebuffer_import_probe.cpp",
            "find_library(android_lib android)",
            "${android_lib}",
            "${vulkan_lib}",
        ),
    )
    require(
        failures,
        "Kotlin host diagnostics",
        host,
        (
            "hardwareBufferProbe",
            "vulkanExternalResourceProbe",
            "vulkanAhardwareBufferImportProbe",
            "VulkanAhardwareBufferImportProbe",
        ),
    )

    require(
        failures,
        "guest receive primitive",
        guest_receive,
        (
            "pocketpc_fd_transport_receive(",
            "pocketpc_graphics_handle_binding_validate_offer(",
            "close(resource_fd);",
            "pocketpc_guest_graphics_received_offer_release",
        ),
    )
    require(
        failures,
        "guest ancillary fd transport",
        fd_transport,
        (
            "SCM_RIGHTS",
            "SOCK_SEQPACKET",
            "FD_CLOEXEC",
        ),
    )
    require(
        failures,
        "guest handle identity binding",
        handle_binding,
        (
            "resource_id",
            "generation",
            "sync_sequence",
            "PGT_STATE_OFFERED_TO_GUEST",
        ),
    )

    require(
        failures,
        "guest graphics fail-closed contract",
        guest,
        (
            "const val canonicalAhardwareBufferImportProbeImplemented =\n        true",
            "const val guestReceivePrimitiveImplemented =\n        true",
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
    for forbidden in ('"raw_pointer"', '"native_window"', '"fd"'):
        if forbidden in descriptor:
            failures.append(
                "descriptor must not define process-local transport field: " + forbidden
            )

    require(
        failures,
        "Vulkan surface backend model",
        surface_backend,
        (
            "PocketPcVulkanSurfaceBackendKind.ANDROID_NATIVE_SURFACE",
            "PocketPcVulkanSurfaceBackendKind.HEADLESS_SURFACE_SHIM",
            "PocketPcVulkanSurfaceBackendKind.VIRTUAL_WSI",
            "implemented = false",
            "ahardwareBufferIsSurface = false",
            "VULKAN_ANDROID_NATIVE_WINDOW_TRANSPORT_NOT_IMPLEMENTED",
            "VULKAN_HEADLESS_PRESENT_CAPTURE_NOT_IMPLEMENTED",
            "VULKAN_VIRTUAL_WSI_NOT_IMPLEMENTED",
        ),
    )

    require(
        failures,
        "WSI visible fail-closed contract",
        wsi,
        (
            "const val headlessDiagnosticSurfaceImplemented =\n        true",
            "const val surfaceCreateImplemented =\n        false",
            "const val presentationSupportImplemented =\n        false",
            "const val implemented =\n        false",
            '"VULKAN_WSI_NOT_IMPLEMENTED"',
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

    rejected_text = json.dumps(arch.get("rejectedRoutes") or [], sort_keys=True)
    for marker in (
        "ANativeWindow",
        "AHardwareBuffer as VkSurfaceKHR",
        "GDI window_surface.flush",
        "wineandroid.drv",
        "Android-to-Android AHardwareBuffer",
        "headless diagnostic",
        "external-memory extension mapping",
    ):
        if marker not in rejected_text:
            failures.append(f"rejected route missing: {marker}")

    if failures:
        print("AHARDWAREBUFFER_VULKAN_TRANSPORT_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    print("AHARDWAREBUFFER_VULKAN_TRANSPORT_POLICY_OK")
    print("architecture_schema=5")
    print("android_cross_process_probe_implemented=true")
    print("canonical_ahb_import_query_implemented=true")
    print("guest_receive_primitive_implemented=true")
    print("guest_receive_runtime_integrated=false")
    print("guest_vulkan_import_implemented=false")
    print("guest_synchronization_implemented=false")
    print("headless_diagnostic_implemented=true")
    print("visible_surface_backend_runnable=false")
    print("wine_visible_vulkan_wsi_implemented=false")
    print("physical_execution_evidence=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
