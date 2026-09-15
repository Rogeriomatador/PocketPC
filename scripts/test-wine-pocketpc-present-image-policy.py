#!/usr/bin/env python3
"""Static guard for the PocketPC exact-presented-image Wine patch."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATCHER = ROOT / "scripts/prepare-wine-pocketpc-present-image.py"


def need(text: str, marker: str, label: str) -> None:
    if marker not in text:
        raise SystemExit(f"POCKETPC_PRESENT_IMAGE_POLICY_MISSING:{label}")


def forbidden(text: str, marker: str, label: str) -> None:
    if marker in text:
        raise SystemExit(f"POCKETPC_PRESENT_IMAGE_POLICY_FORBIDDEN:{label}")


def main() -> int:
    text = PATCHER.read_text(encoding="utf-8")

    for marker, label in (
        ('V50 = "#define WINE_VULKAN_DRIVER_VERSION 50"', "private-abi-v50"),
        ("p_vulkan_image_presented", "driver-present-image-callback"),
        ("VkImage *pocketpc_host_images", "swapchain-host-image-cache"),
        ("pocketpc_host_image_count", "swapchain-image-count"),
        ("pocketpc_host_image_format", "swapchain-format"),
        ("pocketpc_host_extents", "swapchain-extent"),
        ("p_vkGetSwapchainImagesKHR", "host-swapchain-image-enumeration"),
        ("present_info->pImageIndices", "exact-present-image-index"),
        ("pocketpc_image_index >= pocketpc_swapchain->pocketpc_host_image_count", "index-bounds-check"),
        ('#include "pocketpc_guest_external_image_ownership.h"', "ownership-helper-include"),
        ("pocketpc_guest_external_image_acquire(", "external-acquire"),
        ("pocketpc_guest_external_image_release(", "external-release"),
        ("stage=roundtrip_completed", "ownership-roundtrip-marker"),
        ("pocketpc_external_ownership_roundtrip_done", "one-shot-roundtrip-gate"),
        ('"androidInitialReleaseImplemented": True', "android-release-source-integrated"),
        ('"guestPrimitiveActivated": True', "guest-ownership-source-activated"),
        ('"roundTripSourceIntegrated": True', "ownership-roundtrip-source-integrated"),
        ('"executed": False', "ownership-not-executed"),
        ("pixels_copied=0", "no-copy-classification"),
        ('"pixelCopyImplemented": False', "evidence-copy-false"),
        ('"hostVisiblePresentImplemented": False', "evidence-visible-false"),
        ('"robloxExecuted": False', "evidence-roblox-false"),
        ("POCKETPC_EXACT_PRESENTED_IMAGE_SOURCE_INTEGRATED_NOT_EXECUTED", "not-executed-classification"),
    ):
        need(text, marker, label)

    for marker, label in (
        ("ANativeWindow_fromSurface", "raw-anativewindow-shortcut"),
        ("vkCreateAndroidSurfaceKHR", "raw-android-surface-shortcut"),
        ("pixelCopyImplemented\": True", "false-copy-promotion"),
        ("hostVisiblePresentImplemented\": True", "false-visible-promotion"),
        ("robloxExecuted\": True", "false-roblox-promotion"),
    ):
        forbidden(text, marker, label)

    print("POCKETPC_PRESENT_IMAGE_POLICY_OK")
    print("exact_host_swapchain_image_identity_source_integrated=true")
    print("external_ownership_roundtrip_source_integrated=true")
    print("external_ownership_roundtrip_executed=false")
    print("pixel_copy=false")
    print("visible_present=false")
    print("execution_evidence=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
