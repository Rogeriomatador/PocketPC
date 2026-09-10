#!/usr/bin/env python3
"""Guard Wine Vulkan ABI while visible PocketPC WSI remains incomplete."""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DRIVER = ROOT / "third_party/wine/pocketpc-driver"
VULKAN = DRIVER / "vulkan.c"
MAIN = DRIVER / "pocketpcdrv_main.c"
MAKEFILE = DRIVER / "Makefile.in"
PREPARER = ROOT / "scripts/prepare-wine-pocketpc-driver.py"
CONTRACT = (
    ROOT
    / "app/src/main/java/dev/pocketpc/core/runtime/PocketPcVulkanWsiContract.kt"
)
ARCHITECTURE = ROOT / "third_party/wine/POCKETPC_VULKAN_WSI_ARCHITECTURE.json"


def require_contains(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise SystemExit(f"POCKETPC_VULKAN_ABI_POLICY_MISSING:{label}")


def require_absent(text: str, needle: str, label: str) -> None:
    if needle in text:
        raise SystemExit(f"POCKETPC_VULKAN_ABI_POLICY_FORBIDDEN:{label}")


def main() -> int:
    vulkan = VULKAN.read_text(encoding="utf-8")
    main_source = MAIN.read_text(encoding="utf-8")
    makefile = MAKEFILE.read_text(encoding="utf-8")
    preparer = PREPARER.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")
    architecture = json.loads(ARCHITECTURE.read_text(encoding="utf-8"))

    if architecture.get("schemaVersion") != 5:
        raise SystemExit("POCKETPC_VULKAN_ABI_POLICY_ARCHITECTURE_SCHEMA_MISMATCH")

    require_contains(
        main_source,
        ".pVulkanInit =\n        POCKETPC_VulkanInit,",
        "user-driver-vulkan-init",
    )
    require_contains(makefile, "\tvulkan.c \\", "vulkan-source-build")
    require_contains(preparer, '"vulkan.c",', "overlay-vulkan-source")
    require_contains(
        preparer,
        '"vulkanAbiEntryPointImplemented": True',
        "overlay-abi-state",
    )
    require_contains(
        contract,
        "const val abiEntryPointImplemented =\n        true",
        "contract-abi-entrypoint",
    )
    require_contains(
        contract,
        "const val externalHandleExtensionMappingImplemented =\n        true",
        "contract-external-handle-mapping",
    )
    require_contains(
        contract,
        "const val headlessDiagnosticSurfaceImplemented =\n        true",
        "contract-headless-diagnostic",
    )
    require_contains(
        contract,
        "const val surfaceCreateImplemented =\n        false",
        "contract-visible-surface-blocked",
    )
    require_contains(
        contract,
        "const val presentationSupportImplemented =\n        false",
        "contract-visible-presentation-blocked",
    )
    require_contains(
        contract,
        "const val surfaceExtensionMappingImplemented =\n        false",
        "contract-visible-extension-mapping-blocked",
    )
    require_contains(
        contract,
        "const val implemented =\n        false",
        "contract-visible-wsi-blocked",
    )

    require_contains(vulkan, "WINE_VULKAN_DRIVER_VERSION", "wine-vulkan-version")
    require_contains(
        vulkan,
        ".p_vulkan_surface_create = pocketpc_vulkan_surface_create,",
        "surface-create-callback",
    )
    require_contains(
        vulkan,
        ".p_get_physical_device_presentation_support =",
        "presentation-support-callback",
    )
    require_contains(
        vulkan,
        ".p_map_instance_extensions = pocketpc_map_instance_extensions,",
        "instance-extension-callback",
    )
    require_contains(
        vulkan,
        ".p_map_device_extensions = pocketpc_map_device_extensions,",
        "device-extension-callback",
    )

    # Production remains fail closed unless the explicit diagnostic switch is on.
    require_contains(
        vulkan,
        'getenv("POCKETPC_VULKAN_HEADLESS_DIAGNOSTIC")',
        "headless-env-gate",
    )
    require_contains(
        vulkan,
        'strcmp(value, "1")',
        "strict-headless-env-value",
    )
    require_contains(
        vulkan,
        "if (!pocketpc_headless_diagnostic_enabled())",
        "production-headless-guard",
    )
    require_contains(
        vulkan,
        "return VK_ERROR_INCOMPATIBLE_DRIVER;",
        "production-surface-fail-closed",
    )
    require_contains(
        vulkan,
        "return VK_FALSE;",
        "production-presentation-fail-closed",
    )

    # Diagnostic path follows Wine nulldrv semantics but is explicitly invisible.
    require_contains(
        vulkan,
        "VkHeadlessSurfaceCreateInfoEXT",
        "headless-create-info",
    )
    require_contains(
        vulkan,
        "instance->p_vkCreateHeadlessSurfaceEXT",
        "headless-create-call",
    )
    require_contains(
        vulkan,
        "client_surface_create(",
        "headless-client-surface",
    )
    require_contains(
        vulkan,
        "diagnostic_only=1",
        "headless-diagnostic-sentinel",
    )
    require_contains(
        vulkan,
        "visible_present=0",
        "headless-invisible-sentinel",
    )
    require_contains(
        vulkan,
        "extensions->has_VK_KHR_win32_surface",
        "diagnostic-win32-surface-input",
    )
    require_contains(
        vulkan,
        "extensions->has_VK_EXT_headless_surface = 1",
        "diagnostic-headless-mapping",
    )

    # External-handle translation mirrors Wine Linux drivers and is not WSI proof.
    for marker, label in (
        ("has_VK_KHR_external_memory_win32", "external-memory-win32"),
        ("has_VK_KHR_external_memory_fd = 1", "external-memory-fd"),
        ("has_VK_KHR_external_semaphore_win32", "external-semaphore-win32"),
        ("has_VK_KHR_external_semaphore_fd = 1", "external-semaphore-fd"),
        ("has_VK_KHR_external_fence_win32", "external-fence-win32"),
        ("has_VK_KHR_external_fence_fd = 1", "external-fence-fd"),
    ):
        require_contains(vulkan, marker, label)

    require_absent(
        vulkan,
        "vkCreateAndroidSurfaceKHR",
        "raw-android-surface-shortcut",
    )
    require_absent(
        vulkan,
        "has_VK_KHR_android_surface = 1",
        "android-surface-extension-promotion",
    )
    require_absent(
        vulkan,
        "ANativeWindow_fromSurface",
        "raw-anativewindow-shortcut",
    )

    guard_pos = vulkan.find("if (!pocketpc_headless_diagnostic_enabled())")
    create_pos = vulkan.find("instance->p_vkCreateHeadlessSurfaceEXT")
    if guard_pos < 0 or create_pos < 0 or guard_pos > create_pos:
        raise SystemExit("POCKETPC_VULKAN_ABI_POLICY_HEADLESS_GUARD_ORDER_INVALID")

    wine = architecture.get("wine", {})
    gates = architecture.get("gates", {})
    if wine.get("abiEntryPointImplemented") is not True:
        raise SystemExit("POCKETPC_VULKAN_ABI_POLICY_MISSING:architecture-abi-entrypoint")
    if wine.get("externalHandleFdMappingImplemented") is not True:
        raise SystemExit("POCKETPC_VULKAN_ABI_POLICY_MISSING:architecture-external-fd-mapping")
    if wine.get("headlessDiagnosticSurfaceImplemented") is not True:
        raise SystemExit("POCKETPC_VULKAN_ABI_POLICY_MISSING:architecture-headless-diagnostic")
    if wine.get("surfaceCreateImplemented") is not False:
        raise SystemExit("POCKETPC_VULKAN_ABI_POLICY_STATE_MISMATCH:visible-surface")
    if wine.get("presentationSupportImplemented") is not False:
        raise SystemExit("POCKETPC_VULKAN_ABI_POLICY_STATE_MISMATCH:visible-presentation")
    if wine.get("swapchainPresentationImplemented") is not False:
        raise SystemExit("POCKETPC_VULKAN_ABI_POLICY_STATE_MISMATCH:visible-swapchain")
    if gates.get("wineVisibleVulkanWsiImplemented") is not False:
        raise SystemExit("POCKETPC_VULKAN_ABI_POLICY_STATE_MISMATCH:visible-wsi")
    if gates.get("wineHeadlessDiagnosticSoftwareTestExecuted") is not False:
        raise SystemExit("POCKETPC_VULKAN_ABI_POLICY_STATE_MISMATCH:headless-test")

    print(
        "POCKETPC_VULKAN_ABI_POLICY_OK "
        "abi_entrypoint=true "
        "external_handle_fd_mapping=true "
        "headless_diagnostic_implemented=true "
        "headless_diagnostic_executed=false "
        "visible_wsi_implemented=false "
        "wine_vulkan_driver_version=47"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
