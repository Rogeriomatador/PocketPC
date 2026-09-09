#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
ARCH = ROOT / "third_party/wine/POCKETPC_VULKAN_WSI_ARCHITECTURE.json"
NATIVE = ROOT / "app/src/main/cpp/runtime_host.cpp"
CMAKE = ROOT / "app/src/main/cpp/CMakeLists.txt"
HOST = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/NativeRuntimeHost.kt"
WSI = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/PocketPcVulkanWsiContract.kt"
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

    if arch.get("status") != (
        "AHARDWAREBUFFER_TRANSPORT_FOUNDATION_IMPLEMENTED_NOT_EXECUTED_"
        "WSI_NOT_IMPLEMENTED"
    ):
        failures.append("architecture status changed")

    wine = arch.get("wine") or {}
    if (
        wine.get("version") != "11.0"
        or wine.get("commit") != "db11d0fe6a169c457e23d007e20404643d067aa8"
        or wine.get("vulkanDriverVersion") != 47
        or wine.get("requiredEntryPoint") != "user_driver_funcs.pVulkanInit"
    ):
        failures.append("Wine Vulkan ABI contract changed")

    if box64.get("version") != "0.4.4" or box64.get("commit") != (
        "2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a"
    ):
        failures.append("Box64 source lock changed")

    gates = arch.get("gates") or {}
    expected_true = (
        "box64AndroidVulkanWrapperSourceReviewed",
        "ahardwareBufferHostProbeImplemented",
    )
    expected_false = (
        "ahardwareBufferHostProbeSoftwareTestExecuted",
        "ahardwareBufferHostProbePhysicalTestExecuted",
        "ahardwareBufferCrossProcessPhysicalTestExecuted",
        "guestHardwareBufferReceiveImplemented",
        "wineVulkanWsiImplemented",
        "wineVulkanWsiSoftwareTestExecuted",
        "wineVulkanWsiPhysicalTestExecuted",
        "d3d11PresentHostVisibleFrameExecuted",
        "controlledDxvkApplicationAttemptAllowed",
    )

    for key in expected_true:
        if gates.get(key) is not True:
            failures.append(f"expected true gate: {key}")
    for key in expected_false:
        if gates.get(key) is not False:
            failures.append(f"expected false gate: {key}")

    native = NATIVE.read_text(encoding="utf-8")
    cmake = CMAKE.read_text(encoding="utf-8")
    host = HOST.read_text(encoding="utf-8")
    wsi = WSI.read_text(encoding="utf-8")

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
            "VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME",
        ),
    )
    require(
        failures,
        "native host linkage",
        cmake,
        (
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
        "WSI fail-closed contract",
        wsi,
        (
            "const val implemented =",
            "false",
            '"VULKAN_WSI_NOT_IMPLEMENTED"',
        ),
    )

    rejected = arch.get("rejectedRoutes") or []
    rejected_text = json.dumps(rejected, sort_keys=True)
    for marker in (
        "ANativeWindow",
        "GDI window_surface.flush",
        "wineandroid.drv",
    ):
        if marker not in rejected_text:
            failures.append(f"rejected route missing: {marker}")

    if failures:
        print("AHARDWAREBUFFER_VULKAN_TRANSPORT_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    print("AHARDWAREBUFFER_VULKAN_TRANSPORT_POLICY_OK")
    print("host_probe_implemented=true")
    print("host_probe_software_test_executed=false")
    print("host_probe_physical_test_executed=false")
    print("guest_receive_implemented=false")
    print("wine_vulkan_wsi_implemented=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
