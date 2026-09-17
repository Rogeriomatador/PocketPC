#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
PREPARER = ROOT / "scripts/prepare-wine-pocketpc-driver.py"
ARCH = ROOT / "third_party/wine/POCKETPC_VULKAN_WSI_ARCHITECTURE.json"
LOCK = ROOT / "third_party/wine/LOCK.json"


def require(failures: list[str], text: str, markers: tuple[str, ...]) -> None:
    for marker in markers:
        if marker not in text:
            failures.append("missing: " + marker)


def main() -> int:
    failures: list[str] = []
    preparer = PREPARER.read_text(encoding="utf-8")
    arch = json.loads(ARCH.read_text(encoding="utf-8"))
    lock = json.loads(LOCK.read_text(encoding="utf-8"))

    if lock.get("version") != "11.0" or lock.get("commit") != "db11d0fe6a169c457e23d007e20404643d067aa8":
        failures.append("Wine source lock changed")
    if arch.get("schemaVersion") != 6:
        failures.append("Vulkan architecture schema changed")

    require(failures, preparer, (
        'WIN32U_VULKAN_INCLUDE_ANCHOR = "#include <unistd.h>"',
        'WIN32U_PRESENT_ANCHOR = (',
        'res = device->p_vkQueuePresentKHR( queue->host.queue, present_info );',
        'POCKETPC_VULKAN_PRESENT_CONTEXT_DIAGNOSTIC',
        'strcmp(pocketpc_present_context, "1")',
        'POCKETPC_VULKAN_PRESENT_CONTEXT diagnostic_only=1 visible_present=0',
        'present_info->swapchainCount',
        'present_info->pImageIndices[0]',
        'patch_before_once(',
        'source / "dlls/win32u/vulkan.c"',
        '"copiesImage": False',
        '"visiblePresent": False',
        '"patched win32u compilation"',
        '"win32u Present context observer through Wine"',
        '"swapchain image capture"',
    ))

    if "VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL" in preparer or "vkCmdCopyImage" in preparer:
        failures.append("observer policy unexpectedly contains image-copy implementation")

    present = arch.get("presentCapture") or {}
    gates = arch.get("gates") or {}
    if present.get("swapchainImageCaptureHookImplemented") is not False:
        failures.append("architecture must not claim swapchain capture")
    if present.get("copyPresentedImageToPocketPcResourceImplemented") is not False:
        failures.append("architecture must not claim image copy")
    if present.get("hostVisibleFrameImplemented") is not False:
        failures.append("architecture must not claim visible frame")
    if gates.get("swapchainImageCaptureHookImplemented") is not False:
        failures.append("capture gate must remain false")
    if gates.get("hostVisiblePresentImplemented") is not False:
        failures.append("visible Present gate must remain false")

    if failures:
        print("WINE_PRESENT_CONTEXT_OBSERVER_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    print("WINE_PRESENT_CONTEXT_OBSERVER_POLICY_OK")
    print("wine_commit=db11d0fe6a169c457e23d007e20404643d067aa8")
    print("present_context_observer_implemented=true")
    print("swapchain_image_capture_implemented=false")
    print("visible_present_implemented=false")
    print("execution_evidence=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
