#!/usr/bin/env python3
"""Overlay the PocketPC USER driver into the exact pinned Wine 11.0 source.

This mutates only the supplied Wine checkout, which must be outside the
PocketPC repository and exactly at the pinned commit. It does not build Wine
and does not promote any runtime gate.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "third_party/wine/LOCK.json"
TEMPLATE = ROOT / "third_party/wine/pocketpc-driver"
BRIDGE = ROOT / "third_party/wine/pocketpc-display-bridge"

DRIVER_FILES = (
    "Makefile.in",
    "unixlib.h",
    "pocketpcdrv_dll.h",
    "dllmain.c",
    "input.c",
    "pocketpcdrv.h",
    "pocketpcdrv_main.c",
    "surface.c",
    "vulkan.c",
    "window.c",
)

BRIDGE_FILES = (
    "pocketpc_display_bridge.h",
    "pocketpc_display_bridge.c",
    "pocketpc_graphics_transport.h",
    "pocketpc_graphics_transport.c",
    "pocketpc_fd_transport.h",
    "pocketpc_fd_transport.c",
    "pocketpc_graphics_handle_binding.h",
    "pocketpc_graphics_handle_binding.c",
    "pocketpc_guest_graphics_receive.h",
    "pocketpc_guest_graphics_receive.c",
    "pocketpc_external_image_fd_protocol.h",
    "pocketpc_external_image_fd_protocol.c",
    "pocketpc_guest_vulkan_import.h",
    "pocketpc_guest_vulkan_import.c",
    "pocketpc_external_timeline_semaphore_fd_protocol.h",
    "pocketpc_external_timeline_semaphore_fd_protocol.c",
    "pocketpc_guest_vulkan_timeline_semaphore.h",
    "pocketpc_guest_vulkan_timeline_semaphore.c",
    "pocketpc_surface_writer.h",
    "pocketpc_surface_writer.c",
    "pocketpc_wine_window_map.h",
    "pocketpc_wine_window_map.c",
    "pocketpc_wine_window_bridge.h",
    "pocketpc_wine_window_bridge.c",
)

UNIX_ONLY_C_FILES = {
    "pocketpc_display_bridge.c",
    "pocketpc_graphics_transport.c",
    "pocketpc_fd_transport.c",
    "pocketpc_graphics_handle_binding.c",
    "pocketpc_guest_graphics_receive.c",
    "pocketpc_external_image_fd_protocol.c",
    "pocketpc_guest_vulkan_import.c",
    "pocketpc_external_timeline_semaphore_fd_protocol.c",
    "pocketpc_guest_vulkan_timeline_semaphore.c",
    "pocketpc_surface_writer.c",
    "pocketpc_wine_window_map.c",
    "pocketpc_wine_window_bridge.c",
}

UNIX_MAKEDEP_PREAMBLE = """#if 0
#pragma makedep unix
#endif

"""

CONFIGURE_AC_ANCHOR = "WINE_CONFIG_MAKEFILE(dlls/wineandroid.drv)"
CONFIGURE_AC_LINE = "WINE_CONFIG_MAKEFILE(dlls/winepocketpc.drv)"
CONFIGURE_ANCHOR = (
    "wine_fn_config_makefile dlls/wineandroid.drv "
    "enable_wineandroid_drv"
)
CONFIGURE_LINE = (
    "wine_fn_config_makefile dlls/winepocketpc.drv "
    "enable_winepocketpc_drv"
)

WIN32U_VULKAN_INCLUDE_ANCHOR = "#include <unistd.h>"
WIN32U_VULKAN_EXTRA_INCLUDES = "#include <stdlib.h>\n#include <string.h>"
WIN32U_PRESENT_ANCHOR = (
    "    res = device->p_vkQueuePresentKHR( queue->host.queue, present_info );"
)
WIN32U_PRESENT_OBSERVER = r'''    {
        const char *pocketpc_present_context = getenv("POCKETPC_VULKAN_PRESENT_CONTEXT_DIAGNOSTIC");
        if (pocketpc_present_context && !strcmp(pocketpc_present_context, "1") &&
            present_info->swapchainCount && present_info->pImageIndices)
        {
            TRACE("POCKETPC_VULKAN_PRESENT_CONTEXT diagnostic_only=1 visible_present=0 swapchain_count=%u first_image_index=%u\n",
                  present_info->swapchainCount, present_info->pImageIndices[0]);
        }
    }
'''


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            h.update(block)
    return h.hexdigest()


def git_head(source: Path) -> str:
    return subprocess.check_output(
        ["git", "-C", str(source), "rev-parse", "HEAD"],
        text=True,
    ).strip()


def patch_after_once(path: Path, anchor: str, insertion: str) -> bool:
    text = path.read_text(encoding="utf-8")
    if insertion in text:
        return False
    if text.count(anchor) != 1:
        raise RuntimeError(f"PATCH_ANCHOR_INVALID:{path}:{text.count(anchor)}")
    text = text.replace(anchor, anchor + "\n" + insertion, 1)
    path.write_text(text, encoding="utf-8")
    return True


def patch_before_once(path: Path, anchor: str, insertion: str) -> bool:
    text = path.read_text(encoding="utf-8")
    if insertion in text:
        return False
    if text.count(anchor) != 1:
        raise RuntimeError(f"PATCH_ANCHOR_INVALID:{path}:{text.count(anchor)}")
    text = text.replace(anchor, insertion + "\n" + anchor, 1)
    path.write_text(text, encoding="utf-8")
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wine-source", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()

    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    source = args.wine_source.resolve()
    evidence_path = args.evidence.resolve()

    if source == ROOT.resolve() or ROOT.resolve() in source.parents:
        raise SystemExit("WINE_SOURCE_MUST_BE_OUTSIDE_POCKETPC_REPOSITORY")
    if not (source / ".git").exists():
        raise SystemExit("WINE_SOURCE_GIT_CHECKOUT_REQUIRED")
    if git_head(source) != lock["commit"]:
        raise SystemExit("WINE_SOURCE_COMMIT_MISMATCH")

    copying = source / "COPYING.LIB"
    if not copying.is_file():
        raise SystemExit("WINE_LICENSE_FILE_MISSING")
    license_text = copying.read_text(encoding="utf-8", errors="replace")
    if "GNU LESSER GENERAL PUBLIC LICENSE" not in license_text or "Version 2.1" not in license_text:
        raise SystemExit("WINE_LICENSE_EVIDENCE_MISMATCH")

    destination = source / "dlls/winepocketpc.drv"
    if destination.exists():
        raise SystemExit("WINE_POCKETPC_DRIVER_DESTINATION_ALREADY_EXISTS")
    destination.mkdir(parents=True)

    copied: list[dict[str, object]] = []

    for name in DRIVER_FILES:
        src = TEMPLATE / name
        if not src.is_file():
            raise SystemExit(f"DRIVER_TEMPLATE_MISSING:{name}")
        dst = destination / name
        shutil.copyfile(src, dst)
        copied.append(
            {
                "path": f"dlls/winepocketpc.drv/{name}",
                "sha256": digest(dst),
                "bytes": dst.stat().st_size,
                "source": "PocketPC driver template",
            }
        )

    for name in BRIDGE_FILES:
        src = BRIDGE / name
        if not src.is_file():
            raise SystemExit(f"DISPLAY_BRIDGE_SOURCE_MISSING:{name}")
        dst = destination / name
        shutil.copyfile(src, dst)
        if name in UNIX_ONLY_C_FILES:
            original = dst.read_text(encoding="utf-8")
            if "#pragma makedep unix" in original:
                raise SystemExit(f"BRIDGE_SOURCE_ALREADY_HAS_WINE_MAKEDEP:{name}")
            dst.write_text(UNIX_MAKEDEP_PREAMBLE + original, encoding="utf-8")
        copied.append(
            {
                "path": f"dlls/winepocketpc.drv/{name}",
                "sha256": digest(dst),
                "bytes": dst.stat().st_size,
                "source": "PocketPC display/graphics bridge",
            }
        )

    configure_ac = source / "configure.ac"
    configure = source / "configure"
    if not configure_ac.is_file():
        raise SystemExit("WINE_CONFIGURE_AC_MISSING")
    if not configure.is_file():
        raise SystemExit("WINE_CONFIGURE_MISSING")

    ac_changed = patch_after_once(configure_ac, CONFIGURE_AC_ANCHOR, CONFIGURE_AC_LINE)
    configure_changed = patch_after_once(configure, CONFIGURE_ANCHOR, CONFIGURE_LINE)

    win32u_vulkan = source / "dlls/win32u/vulkan.c"
    if not win32u_vulkan.is_file():
        raise SystemExit("WINE_WIN32U_VULKAN_SOURCE_MISSING")
    win32u_include_changed = patch_after_once(
        win32u_vulkan,
        WIN32U_VULKAN_INCLUDE_ANCHOR,
        WIN32U_VULKAN_EXTRA_INCLUDES,
    )
    win32u_present_observer_changed = patch_before_once(
        win32u_vulkan,
        WIN32U_PRESENT_ANCHOR,
        WIN32U_PRESENT_OBSERVER,
    )

    evidence = {
        "schemaVersion": 8,
        "status": "WINE_POCKETPC_DRIVER_OVERLAY_PREPARED_NOT_BUILT_NOT_RUNTIME_TESTED",
        "wineVersion": lock["version"],
        "wineCommit": lock["commit"],
        "driverName": "winepocketpc.drv",
        "unixLibrary": "winepocketpc.so",
        "protocolVersion": 4,
        "guestGraphicsProtocolVersion": 1,
        "graphicsFdTransportProtocolVersion": 1,
        "externalImageFdProtocol": "PVI1",
        "externalImageFdProtocolVersion": 1,
        "externalTimelineSemaphoreFdProtocol": "PVS1",
        "externalTimelineSemaphoreFdProtocolVersion": 1,
        "win32uPresentContextObserver": {
            "implemented": True,
            "source": "dlls/win32u/vulkan.c",
            "environmentGate": "POCKETPC_VULKAN_PRESENT_CONTEXT_DIAGNOSTIC=1",
            "observes": ["swapchainCount", "firstImageIndex"],
            "copiesImage": False,
            "visiblePresent": False,
            "includePatchChanged": win32u_include_changed,
            "observerPatchChanged": win32u_present_observer_changed,
        },
        "graphicsSelection": {
            "registryPath": r"HKCU\Software\Wine\Drivers",
            "valueName": "Graphics",
            "value": "pocketpc",
            "resolvedLibrary": "winepocketpc.drv",
        },
        "callbacksImplemented": [
            "pCreateWindow",
            "pDestroyWindow",
            "pProcessEvents",
            "pCreateWindowSurface",
            "pWindowPosChanging",
            "pWindowPosChanged",
            "pVulkanInit_v47_fail_closed_visible_headless_diagnostic",
        ],
        "surfaceCallbackImplemented": True,
        "inputInjectionImplemented": True,
        "vulkanAbiEntryPointImplemented": True,
        "vulkanAbiDriverVersion": 47,
        "vulkanHeadlessDiagnosticImplemented": True,
        "vulkanHeadlessPresentObserverImplemented": True,
        "vulkanExternalFdExtensionMappingImplemented": True,
        "vulkanVisibleSurfaceCreateImplemented": False,
        "vulkanVisiblePresentationSupportImplemented": False,
        "vulkanDriverProductionImplemented": False,
        "guestGraphicsDescriptorProtocolImplemented": True,
        "guestGraphicsOwnershipProtocolImplemented": True,
        "guestGraphicsAncillaryFdTransportPrimitiveImplemented": True,
        "guestGraphicsHandleBindingImplemented": True,
        "guestGraphicsReceivePrimitiveImplemented": True,
        "externalImagePvi1ProtocolImplemented": True,
        "guestVulkanImportPrimitiveImplemented": True,
        "externalTimelineSemaphorePvs1ProtocolImplemented": True,
        "hostTimelineSemaphoreExporterImplemented": True,
        "guestVulkanTimelineImportPrimitiveImplemented": True,
        "guestTimelineCpuSignalWaitPrimitiveImplemented": True,
        "guestGraphicsHandleReceiveIntegrated": False,
        "guestGraphicsImportIntegrated": False,
        "guestGraphicsSynchronizationImplemented": False,
        "swapchainImageCaptureImplemented": False,
        "openglDriverImplemented": False,
        "configureAcPatched": ac_changed,
        "generatedConfigurePatched": configure_changed,
        "files": copied,
        "wineBuildClassification": {
            "peModuleSources": ["dllmain.c"],
            "unixLibrarySources": [
                "pocketpcdrv_main.c",
                "input.c",
                "surface.c",
                "vulkan.c",
                "window.c",
                "pocketpc_display_bridge.c",
                "pocketpc_graphics_transport.c",
                "pocketpc_fd_transport.c",
                "pocketpc_graphics_handle_binding.c",
                "pocketpc_guest_graphics_receive.c",
                "pocketpc_external_image_fd_protocol.c",
                "pocketpc_guest_vulkan_import.c",
                "pocketpc_external_timeline_semaphore_fd_protocol.c",
                "pocketpc_guest_vulkan_timeline_semaphore.c",
                "pocketpc_surface_writer.c",
                "pocketpc_wine_window_map.c",
                "pocketpc_wine_window_bridge.c",
            ],
            "bridgeSourcesMarkedUnixOnly": True,
        },
        "notExecuted": [
            "Wine configure",
            "winepocketpc.drv compilation",
            "winepocketpc.so compilation",
            "patched win32u compilation",
            "pVulkanInit through Wine",
            "headless diagnostic Vulkan surface through Wine",
            "headless Present observer through Wine",
            "win32u Present context observer through Wine",
            "PVI1 external image receive through Wine",
            "guest Vulkan import primitive through Wine VkDevice",
            "PVS1 timeline semaphore receive through Wine",
            "host timeline semaphore export execution on Android Vulkan device",
            "guest Vulkan timeline semaphore import through Wine VkDevice",
            "timeline semaphore CPU signal/wait round-trip",
            "authenticated guest graphics receive integration",
            "guest graphics Vulkan import integration",
            "guest graphics GPU synchronization",
            "swapchain image capture",
            "visible Wine Vulkan surface creation",
            "visible Vulkan presentation support",
            "Wine driver load",
            "HWND lifecycle through Wine",
            "surface presentation through Wine",
            "input injection through Wine",
            "Box64 execution",
            "Android execution",
            "DXVK/Vulkan Present",
            "Roblox",
        ],
    }

    evidence_path.parent.mkdir(parents=True, exist_ok=True)
    evidence_path.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")

    print("WINE_POCKETPC_DRIVER_OVERLAY_PREPARED_NOT_BUILT")
    print(f"wine_commit={lock['commit']}")
    print("graphics_driver=winepocketpc.drv")
    print("vulkan_abi_entrypoint=true")
    print("vulkan_headless_diagnostic=true")
    print("vulkan_headless_present_observer=true")
    print("win32u_present_context_observer=true")
    print("win32u_present_context_image_copy=false")
    print("vulkan_external_fd_extension_mapping=true")
    print("external_image_fd_protocol=PVI1")
    print("guest_vulkan_import_primitive=true")
    print("external_timeline_semaphore_fd_protocol=PVS1")
    print("host_timeline_semaphore_exporter=true")
    print("guest_vulkan_timeline_import_primitive=true")
    print("guest_timeline_cpu_signal_wait_primitive=true")
    print("guest_graphics_handle_receive_integrated=false")
    print("guest_graphics_import_integrated=false")
    print("guest_graphics_synchronization=false")
    print("swapchain_image_capture=false")
    print("visible_vulkan_surface_implemented=false")
    print("runtime_execution_evidence=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
