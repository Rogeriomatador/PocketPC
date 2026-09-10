#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
PROTOCOL = ROOT / "third_party/wine/POCKETPC_DISPLAY_BRIDGE_PROTOCOL.json"
AUDIT = ROOT / "third_party/wine/ANDROID_DRIVER_REUSE.json"
ARCH = ROOT / "third_party/wine/POCKETPC_VULKAN_WSI_ARCHITECTURE.json"
CONTRACT = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/PocketPcVulkanWsiContract.kt"
GUEST = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GuestGraphicsTransportContract.kt"
REQUIREMENTS = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GuestProbeRequirements.kt"
READINESS = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/PcRuntimeReadiness.kt"
EVIDENCE = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeProbeEvidenceStore.kt"
SUITE = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeDiagnosticSuite.kt"
ATTEMPT = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/PcWindowsLaunchAttemptPlan.kt"
DRIVER_MAIN = ROOT / "third_party/wine/pocketpc-driver/pocketpcdrv_main.c"
DRIVER_VULKAN = ROOT / "third_party/wine/pocketpc-driver/vulkan.c"


def require(failures: list[str], label: str, text: str, markers: tuple[str, ...]) -> None:
    for marker in markers:
        if marker not in text:
            failures.append(f"{label} missing: {marker}")


def main() -> int:
    failures: list[str] = []
    try:
        protocol = json.loads(PROTOCOL.read_text(encoding="utf-8"))
        audit = json.loads(AUDIT.read_text(encoding="utf-8"))
        arch = json.loads(ARCH.read_text(encoding="utf-8"))
    except Exception as error:
        print("VULKAN_WSI_POLICY_FAILED", file=sys.stderr)
        print(f"- json: {error}", file=sys.stderr)
        return 1

    if arch.get("schemaVersion") != 6:
        failures.append("PocketPC Vulkan architecture must remain schema 6")

    for label, document in (("protocol", protocol), ("audit", audit)):
        gates = document.get("gates") or {}
        if gates.get("vulkanWsiImplemented") is not False:
            failures.append(f"{label} must keep vulkanWsiImplemented=false")
        for key in (
            "vulkanWsiSoftwareTestExecuted",
            "vulkanWsiPhysicalTestExecuted",
            "d3d11PresentProbeSoftwareTestExecuted",
        ):
            if gates.get(key) is not False:
                failures.append(f"{label} expected false gate: {key}")
        if gates.get("d3d11PresentProbeFailClosedImplemented") is not True:
            failures.append(f"{label} missing Present fail-closed gate")

    legacy_wsi = protocol.get("vulkanWsi") or {}
    if legacy_wsi.get("wineVulkanDriverVersion") != 47:
        failures.append("Display protocol Wine Vulkan ABI version changed")
    if legacy_wsi.get("evidenceNamespace") != "runtime-probe-evidence-v9":
        failures.append("runtime Vulkan evidence namespace changed")

    texts = {
        "contract": CONTRACT.read_text(encoding="utf-8"),
        "guest": GUEST.read_text(encoding="utf-8"),
        "requirements": REQUIREMENTS.read_text(encoding="utf-8"),
        "readiness": READINESS.read_text(encoding="utf-8"),
        "evidence": EVIDENCE.read_text(encoding="utf-8"),
        "suite": SUITE.read_text(encoding="utf-8"),
        "attempt": ATTEMPT.read_text(encoding="utf-8"),
        "driver main": DRIVER_MAIN.read_text(encoding="utf-8"),
        "driver vulkan": DRIVER_VULKAN.read_text(encoding="utf-8"),
    }

    require(failures, "WSI contract", texts["contract"], (
        "WINE_VULKAN_DRIVER_VERSION =",
        "47",
        "const val headlessDiagnosticSurfaceImplemented =",
        "const val surfaceCreateImplemented =",
        "const val presentationSupportImplemented =",
        "const val implemented =",
        '"VULKAN_WSI_NOT_IMPLEMENTED"',
        '"p_vulkan_surface_create"',
        '"p_get_physical_device_presentation_support"',
        '"p_map_instance_extensions"',
        '"p_map_device_extensions"',
    ))
    require(failures, "guest transport", texts["guest"], (
        "const val externalImagePvi1ProtocolImplemented = true",
        "const val guestVulkanImportPrimitiveImplemented = true",
        "const val externalTimelineSemaphorePvs1ProtocolImplemented = true",
        "const val hostTimelineSemaphoreExporterImplemented = true",
        "const val guestVulkanTimelineImportPrimitiveImplemented = true",
        "const val guestReceiveImplemented = false",
        "const val guestImportImplemented = false",
        "const val synchronizationImplemented = false",
    ))
    require(failures, "Present requirement", texts["requirements"], (
        "D3D11_PRESENT_SMOKE",
        "PocketPcVulkanWsiContract",
        ".implemented",
        ".blocker",
    ))
    require(failures, "runtime readiness", texts["readiness"], (
        "graphics-bridge",
        "Direct3D → Vulkan / WSI",
        "NOT_IMPLEMENTED",
        "pVulkanInit",
    ))
    require(failures, "evidence invalidation", texts["evidence"], (
        "PocketPcVulkanWsiContract",
        '"runtime-probe-evidence-v9"',
        "D3D11_PRESENT_SMOKE",
    ))
    require(failures, "diagnostic suite", texts["suite"], (
        "D3D11_PRESENT_SMOKE",
        "D3D11_SMOKE",
    ))
    require(failures, "controlled launch", texts["attempt"], (
        "PocketPcVulkanWsiContract",
        "GRAPHICS_PRESENTATION_NOT_VALIDATED",
    ))
    require(failures, "driver registration", texts["driver main"], (
        ".pVulkanInit =",
        "POCKETPC_VulkanInit",
    ))

    vulkan = texts["driver vulkan"]
    require(failures, "driver Vulkan", vulkan, (
        "WINE_VULKAN_DRIVER_VERSION",
        'getenv("POCKETPC_VULKAN_HEADLESS_DIAGNOSTIC")',
        "return VK_ERROR_INCOMPATIBLE_DRIVER;",
        "return VK_FALSE;",
        "VkHeadlessSurfaceCreateInfoEXT",
        "instance->p_vkCreateHeadlessSurfaceEXT",
        "client_surface_create(",
        "stage=headless_present_observed",
        "diagnostic_only=1",
        "visible_present=0",
        "has_VK_KHR_external_memory_fd = 1",
        "has_VK_KHR_external_semaphore_fd = 1",
        "has_VK_KHR_external_fence_fd = 1",
    ))
    for forbidden in (
        "vkCreateAndroidSurfaceKHR",
        "has_VK_KHR_android_surface = 1",
        "ANativeWindow_fromSurface",
    ):
        if forbidden in vulkan:
            failures.append(f"premature visible WSI shortcut: {forbidden}")

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
    ):
        if gates.get(key) is not False:
            failures.append(f"visible/runtime gate must remain false: {key}")

    headless = (arch.get("surfaceBackend") or {}).get("headlessDiagnostic") or {}
    if headless.get("implemented") is not True or headless.get("visible") is not False:
        failures.append("headless diagnostic must be implemented but explicitly invisible")
    if headless.get("mayPromoteRoblox") is not False:
        failures.append("headless diagnostic must never promote Roblox")

    if failures:
        print("VULKAN_WSI_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    print("VULKAN_WSI_POLICY_OK")
    print("wine_vulkan_driver_version=47")
    print("pvi1_implemented=true")
    print("pvs1_implemented=true")
    print("headless_present_observer_implemented=true")
    print("headless_visible=false")
    print("gpu_queue_synchronization=false")
    print("swapchain_capture=false")
    print("host_visible_present=false")
    print("roblox_executed=false")
    print("execution_evidence=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
