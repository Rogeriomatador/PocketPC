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
    "window.c",
)

BRIDGE_FILES = (
    "pocketpc_display_bridge.h",
    "pocketpc_display_bridge.c",
    "pocketpc_wine_window_map.h",
    "pocketpc_wine_window_map.c",
    "pocketpc_wine_window_bridge.h",
    "pocketpc_wine_window_bridge.c",
)

UNIX_ONLY_C_FILES = {
    "pocketpc_display_bridge.c",
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


def patch_once(
    path: Path,
    anchor: str,
    line: str,
) -> bool:
    text = path.read_text(encoding="utf-8")
    if line in text:
        return False
    if text.count(anchor) != 1:
        raise RuntimeError(
            f"PATCH_ANCHOR_INVALID:{path.name}:{text.count(anchor)}"
        )
    text = text.replace(
        anchor,
        anchor + "\n" + line,
        1,
    )
    path.write_text(
        text,
        encoding="utf-8",
    )
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--wine-source",
        type=Path,
        required=True,
    )
    parser.add_argument(
        "--evidence",
        type=Path,
        required=True,
    )
    args = parser.parse_args()

    lock = json.loads(
        LOCK.read_text(encoding="utf-8")
    )
    source = args.wine_source.resolve()
    evidence_path = args.evidence.resolve()

    if (
        source == ROOT.resolve()
        or ROOT.resolve() in source.parents
    ):
        raise SystemExit(
            "WINE_SOURCE_MUST_BE_OUTSIDE_POCKETPC_REPOSITORY"
        )
    if not (source / ".git").exists():
        raise SystemExit(
            "WINE_SOURCE_GIT_CHECKOUT_REQUIRED"
        )
    if git_head(source) != lock["commit"]:
        raise SystemExit(
            "WINE_SOURCE_COMMIT_MISMATCH"
        )

    copying = source / "COPYING.LIB"
    if not copying.is_file():
        raise SystemExit(
            "WINE_LICENSE_FILE_MISSING"
        )
    license_text = copying.read_text(
        encoding="utf-8",
        errors="replace",
    )
    if (
        "GNU LESSER GENERAL PUBLIC LICENSE"
        not in license_text
        or "Version 2.1" not in license_text
    ):
        raise SystemExit(
            "WINE_LICENSE_EVIDENCE_MISMATCH"
        )

    destination = (
        source /
        "dlls/winepocketpc.drv"
    )
    if destination.exists():
        raise SystemExit(
            "WINE_POCKETPC_DRIVER_DESTINATION_ALREADY_EXISTS"
        )
    destination.mkdir(parents=True)

    copied: list[dict[str, object]] = []

    for name in DRIVER_FILES:
        src = TEMPLATE / name
        if not src.is_file():
            raise SystemExit(
                f"DRIVER_TEMPLATE_MISSING:{name}"
            )
        dst = destination / name
        shutil.copyfile(src, dst)
        if name in UNIX_ONLY_C_FILES:
            original = dst.read_text(
                encoding="utf-8",
            )
            if "#pragma makedep unix" in original:
                raise SystemExit(
                    f"BRIDGE_SOURCE_ALREADY_HAS_WINE_MAKEDEP:{name}"
                )
            dst.write_text(
                UNIX_MAKEDEP_PREAMBLE +
                original,
                encoding="utf-8",
            )
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
            raise SystemExit(
                f"DISPLAY_BRIDGE_SOURCE_MISSING:{name}"
            )
        dst = destination / name
        shutil.copyfile(src, dst)
        copied.append(
            {
                "path": f"dlls/winepocketpc.drv/{name}",
                "sha256": digest(dst),
                "bytes": dst.stat().st_size,
                "source": "PocketPC display bridge",
            }
        )

    configure_ac = source / "configure.ac"
    configure = source / "configure"
    if not configure_ac.is_file():
        raise SystemExit(
            "WINE_CONFIGURE_AC_MISSING"
        )
    if not configure.is_file():
        raise SystemExit(
            "WINE_CONFIGURE_MISSING"
        )

    ac_changed = patch_once(
        configure_ac,
        CONFIGURE_AC_ANCHOR,
        CONFIGURE_AC_LINE,
    )
    configure_changed = patch_once(
        configure,
        CONFIGURE_ANCHOR,
        CONFIGURE_LINE,
    )

    evidence = {
        "schemaVersion": 1,
        "status": "WINE_POCKETPC_DRIVER_OVERLAY_PREPARED_NOT_BUILT_NOT_RUNTIME_TESTED",
        "wineVersion": lock["version"],
        "wineCommit": lock["commit"],
        "driverName": "winepocketpc.drv",
        "unixLibrary": "winepocketpc.so",
        "protocolVersion": 2,
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
            "pWindowPosChanging",
            "pWindowPosChanged",
        ],
        "surfaceCallbackImplemented": False,
        "inputInjectionImplemented": True,
        "vulkanDriverImplemented": False,
        "openglDriverImplemented": False,
        "configureAcPatched": ac_changed,
        "generatedConfigurePatched": configure_changed,
        "files": copied,
        "wineBuildClassification": {
            "peModuleSources": [
                "dllmain.c"
            ],
            "unixLibrarySources": [
                "pocketpcdrv_main.c",
                "window.c",
                "pocketpc_display_bridge.c",
                "pocketpc_wine_window_map.c",
                "pocketpc_wine_window_bridge.c"
            ],
            "bridgeSourcesMarkedUnixOnly": True
        },
        "notExecuted": [
            "Wine configure",
            "winepocketpc.drv compilation",
            "winepocketpc.so compilation",
            "Wine driver load",
            "HWND lifecycle through Wine",
            "surface presentation through Wine",
            "input injection through Wine",
            "Box64 execution",
            "Android execution",
            "DXVK/Vulkan",
            "Roblox",
        ],
    }

    evidence_path.parent.mkdir(
        parents=True,
        exist_ok=True,
    )
    evidence_path.write_text(
        json.dumps(
            evidence,
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )

    print(
        "WINE_POCKETPC_DRIVER_OVERLAY_PREPARED_NOT_BUILT"
    )
    print(
        f"wine_commit={lock['commit']}"
    )
    print(
        "graphics_driver=winepocketpc.drv"
    )
    print(
        "runtime_execution_evidence=false"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
