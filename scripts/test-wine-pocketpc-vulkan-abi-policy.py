#!/usr/bin/env python3
"""Fail closed while PocketPC's Wine Vulkan WSI is still incomplete."""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DRIVER = ROOT / "third_party/wine/pocketpc-driver"
VULKAN = DRIVER / "vulkan.c"
MAIN = DRIVER / "pocketpcdrv_main.c"
MAKEFILE = DRIVER / "Makefile.in"
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
    contract = CONTRACT.read_text(encoding="utf-8")
    architecture = json.loads(ARCHITECTURE.read_text(encoding="utf-8"))

    require_contains(
        main_source,
        ".pVulkanInit =\n        POCKETPC_VulkanInit,",
        "user-driver-vulkan-init",
    )
    require_contains(makefile, "\tvulkan.c \\", "vulkan-source-build")
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

    wsi_implemented = "const val implemented =\n        true" in contract
    architecture_implemented = bool(
        architecture.get("gates", {}).get("wineVulkanWsiImplemented")
    )

    if wsi_implemented != architecture_implemented:
        raise SystemExit("POCKETPC_VULKAN_ABI_POLICY_IMPLEMENTATION_STATE_MISMATCH")

    if not wsi_implemented:
        require_contains(
            vulkan,
            "return VK_ERROR_INCOMPATIBLE_DRIVER;",
            "surface-fail-closed",
        )
        require_contains(
            vulkan,
            "return VK_FALSE;",
            "presentation-fail-closed",
        )
        require_contains(
            vulkan,
            "surface_backend=blocked",
            "blocked-evidence-sentinel",
        )
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
            "has_VK_KHR_win32_surface = 1",
            "win32-surface-extension-promotion",
        )

    print(
        "POCKETPC_VULKAN_ABI_POLICY_OK "
        f"implemented={'true' if wsi_implemented else 'false'} "
        "wine_vulkan_driver_version=47"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
