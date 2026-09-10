#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
PROTOCOL = ROOT / "third_party/wine/POCKETPC_DISPLAY_BRIDGE_PROTOCOL.json"
AUDIT = ROOT / "third_party/wine/ANDROID_DRIVER_REUSE.json"
CONTRACT = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/PocketPcVulkanWsiContract.kt"
GUEST_TRANSPORT = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GuestGraphicsTransportContract.kt"
REQUIREMENTS = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GuestProbeRequirements.kt"
READINESS = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/PcRuntimeReadiness.kt"
EVIDENCE = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeProbeEvidenceStore.kt"
SUITE = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeDiagnosticSuite.kt"
ATTEMPT = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/PcWindowsLaunchAttemptPlan.kt"
DRIVER_MAIN = ROOT / "third_party/wine/pocketpc-driver/pocketpcdrv_main.c"
DRIVER_VULKAN = ROOT / "third_party/wine/pocketpc-driver/vulkan.c"
REQUIREMENTS_TEST = ROOT / "app/src/test/java/dev/pocketpc/core/runtime/GuestProbeRequirementsTest.kt"
READINESS_TEST = ROOT / "app/src/test/java/dev/pocketpc/core/runtime/PcRuntimeReadinessTest.kt"
SUITE_TEST = ROOT / "app/src/test/java/dev/pocketpc/core/runtime/RuntimeDiagnosticSuiteTest.kt"


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
        protocol = json.loads(PROTOCOL.read_text(encoding="utf-8"))
        audit = json.loads(AUDIT.read_text(encoding="utf-8"))
    except Exception as error:
        print("VULKAN_WSI_POLICY_FAILED", file=sys.stderr)
        print(f"- json: {error}", file=sys.stderr)
        return 1

    for label, document in (("protocol", protocol), ("audit", audit)):
        gates = document.get("gates") or {}
        if gates.get("vulkanWsiImplemented") is not False:
            failures.append(
                f"{label} must keep vulkanWsiImplemented=false until real visible backend lands"
            )
        for key in (
            "vulkanWsiSoftwareTestExecuted",
            "vulkanWsiPhysicalTestExecuted",
            "d3d11PresentProbeSoftwareTestExecuted",
        ):
            if gates.get(key) is not False:
                failures.append(f"{label} expected false gate: {key}")
        if gates.get("d3d11PresentProbeFailClosedImplemented") is not True:
            failures.append(f"{label} missing Present fail-closed gate")

    wsi = protocol.get("vulkanWsi") or {}
    expected_callbacks = [
        "p_vulkan_surface_create",
        "p_get_physical_device_presentation_support",
        "p_map_instance_extensions",
        "p_map_device_extensions",
    ]
    if (
        wsi.get("status") != "NOT_IMPLEMENTED"
        or wsi.get("wineVulkanDriverVersion") != 47
        or wsi.get("requiredDriverCallbacks") != expected_callbacks
        or wsi.get("evidenceNamespace") != "runtime-probe-evidence-v9"
    ):
        failures.append("Vulkan WSI protocol contract changed without policy update")

    texts = {
        "contract": CONTRACT.read_text(encoding="utf-8"),
        "guest transport": GUEST_TRANSPORT.read_text(encoding="utf-8"),
        "requirements": REQUIREMENTS.read_text(encoding="utf-8"),
        "readiness": READINESS.read_text(encoding="utf-8"),
        "evidence": EVIDENCE.read_text(encoding="utf-8"),
        "suite": SUITE.read_text(encoding="utf-8"),
        "attempt": ATTEMPT.read_text(encoding="utf-8"),
        "driver": DRIVER_MAIN.read_text(encoding="utf-8"),
        "driver vulkan": DRIVER_VULKAN.read_text(encoding="utf-8"),
        "requirements test": REQUIREMENTS_TEST.read_text(encoding="utf-8"),
        "readiness test": READINESS_TEST.read_text(encoding="utf-8"),
        "suite test": SUITE_TEST.read_text(encoding="utf-8"),
    }

    require(
        failures,
        "Vulkan WSI contract",
        texts["contract"],
        (
            "WINE_VULKAN_DRIVER_VERSION =",
            "47",
            'PINNED_WINE_VERSION =\n        "11.0"',
            "db11d0fe6a169c457e23d007e20404643d067aa8",
            "const val abiEntryPointImplemented =\n        true",
            "const val externalHandleExtensionMappingImplemented =\n        true",
            "const val headlessDiagnosticSurfaceImplemented =\n        true",
            "const val headlessDiagnosticPresentationSupportImplemented =\n        true",
            "const val headlessDiagnosticExtensionMappingImplemented =\n        true",
            "const val headlessDiagnosticSoftwareTestExecuted =\n        false",
            "const val surfaceCreateImplemented =\n        false",
            "const val presentationSupportImplemented =\n        false",
            "const val surfaceExtensionMappingImplemented =\n        false",
            "const val swapchainPresentationImplemented =\n        false",
            "const val implemented =\n        false",
            '"VULKAN_WSI_NOT_IMPLEMENTED"',
            '"p_vulkan_surface_create"',
            '"p_get_physical_device_presentation_support"',
            '"p_map_instance_extensions"',
            '"p_map_device_extensions"',
            '"guest-graphics-handle-receive"',
            '"guest-graphics-resource-import"',
            '"guest-graphics-synchronization"',
        ),
    )

    require(
        failures,
        "guest graphics transport contract",
        texts["guest transport"],
        (
            "const val descriptorProtocolImplemented =\n        true",
            "const val guestReceivePrimitiveImplemented =\n        true",
            "const val guestReceiveImplemented =\n        false",
            "const val guestImportImplemented =\n        false",
            "const val synchronizationImplemented =\n        false",
        ),
    )

    require(
        failures,
        "Present probe blocker",
        texts["requirements"],
        (
            "D3D11_PRESENT_SMOKE",
            "PocketPcVulkanWsiContract",
            ".implemented",
            ".blocker",
        ),
    )
    require(
        failures,
        "readiness fail closed",
        texts["readiness"],
        (
            "graphics-bridge",
            "Direct3D → Vulkan / WSI",
            "PcRuntimeStageState",
            ".NOT_IMPLEMENTED",
            "pVulkanInit",
            "GDI window_surface.flush",
        ),
    )
    require(
        failures,
        "evidence invalidation",
        texts["evidence"],
        (
            "PocketPcVulkanWsiContract",
            ".implemented &&",
            '"runtime-probe-evidence-v9"',
            "D3D11_PRESENT_SMOKE",
        ),
    )
    require(
        failures,
        "diagnostic suite blocker",
        texts["suite"],
        (
            "structurallyBlockedProbes",
            "D3D11_PRESENT_SMOKE",
            "D3D11_SMOKE",
        ),
    )
    require(
        failures,
        "controlled attempt blocker",
        texts["attempt"],
        (
            "PocketPcVulkanWsiContract",
            ".blocker",
            "GRAPHICS_PRESENTATION_NOT_VALIDATED",
        ),
    )

    require(
        failures,
        "PocketPC Vulkan ABI registration",
        texts["driver"],
        (
            ".pVulkanInit =",
            "POCKETPC_VulkanInit",
        ),
    )

    vulkan = texts["driver vulkan"]
    require(
        failures,
        "PocketPC Vulkan diagnostic/fail-closed backend",
        vulkan,
        (
            "WINE_VULKAN_DRIVER_VERSION",
            'getenv("POCKETPC_VULKAN_HEADLESS_DIAGNOSTIC")',
            'strcmp(value, "1")',
            "if (!pocketpc_headless_diagnostic_enabled())",
            "return VK_ERROR_INCOMPATIBLE_DRIVER;",
            "return VK_FALSE;",
            "VkHeadlessSurfaceCreateInfoEXT",
            "instance->p_vkCreateHeadlessSurfaceEXT",
            "client_surface_create(",
            "client_surface_release(",
            "diagnostic_only=1",
            "visible_present=0",
            ".p_vulkan_surface_create = pocketpc_vulkan_surface_create,",
            ".p_get_physical_device_presentation_support =",
            ".p_map_instance_extensions = pocketpc_map_instance_extensions,",
            ".p_map_device_extensions = pocketpc_map_device_extensions,",
        ),
    )

    # The diagnostic path may map Win32 surface to VK_EXT_headless_surface, but
    # it must be opt-in and must never map to an Android-visible surface.
    require(
        failures,
        "headless diagnostic extension mapping",
        vulkan,
        (
            "extensions->has_VK_KHR_win32_surface",
            "extensions->has_VK_EXT_headless_surface = 1",
            "extensions->has_VK_KHR_win32_surface = 1",
            "!pocketpc_headless_diagnostic_enabled()",
        ),
    )

    # External Win32 handle translation to fd is valid before visible WSI and
    # mirrors Wine's Linux driver model.
    require(
        failures,
        "external handle fd mapping",
        vulkan,
        (
            "has_VK_KHR_external_memory_win32",
            "has_VK_KHR_external_memory_fd = 1",
            "has_VK_KHR_external_semaphore_win32",
            "has_VK_KHR_external_semaphore_fd = 1",
            "has_VK_KHR_external_fence_win32",
            "has_VK_KHR_external_fence_fd = 1",
            "external_handle_extension_mapping_ready",
        ),
    )

    for forbidden in (
        "vkCreateAndroidSurfaceKHR",
        "has_VK_KHR_android_surface = 1",
        "ANativeWindow_fromSurface",
    ):
        if forbidden in vulkan:
            failures.append(
                "visible Vulkan backend contains forbidden premature promotion: " + forbidden
            )

    production_guard = vulkan.find("if (!pocketpc_headless_diagnostic_enabled())")
    headless_create = vulkan.find("instance->p_vkCreateHeadlessSurfaceEXT")
    if production_guard < 0 or headless_create < 0 or production_guard > headless_create:
        failures.append("headless surface creation is not preceded by the production fail-closed guard")

    require(
        failures,
        "requirements tests",
        texts["requirements test"],
        (
            "d3d11PresentFailsClosedUntilVulkanWsiExists",
            "VULKAN_WSI_NOT_IMPLEMENTED",
        ),
    )
    require(
        failures,
        "readiness tests",
        texts["readiness test"],
        (
            "stalePresentEvidenceCannotPromoteGraphicsWithoutWsi",
            "graphicsDeviceWithoutWsiStaysNotImplemented",
        ),
    )
    require(
        failures,
        "suite tests",
        texts["suite test"],
        (
            "presentProbeIsExplicitlyBlockedUntilWsiBackendExists",
            "structurallyBlockedProbes",
        ),
    )

    if failures:
        print("VULKAN_WSI_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    print("VULKAN_WSI_POLICY_OK")
    print("wine_vulkan_driver_version=47")
    print("vulkan_abi_entrypoint_implemented=true")
    print("external_handle_fd_mapping_implemented=true")
    print("headless_diagnostic_backend_implemented=true")
    print("headless_diagnostic_execution=false")
    print("visible_wsi_implemented=false")
    print("guest_graphics_transport_ready=false")
    print("d3d11_visible_present_runnable=false")
    print("evidence_namespace=runtime-probe-evidence-v9")
    print("software_test_execution=false")
    print("physical_execution=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
