#!/usr/bin/env python3
from __future__ import annotations

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
BOX64 = ROOT / "third_party/box64/LOCK.json"
WINE = ROOT / "third_party/wine/LOCK.json"
GRAPHICS = ROOT / "third_party/graphics/LOCK.json"

SHA40 = re.compile(r"^[0-9a-f]{40}$")


def main() -> int:
    failures: list[str] = []
    try:
        box64 = json.loads(BOX64.read_text(encoding="utf-8"))
        wine = json.loads(WINE.read_text(encoding="utf-8"))
        graphics = json.loads(GRAPHICS.read_text(encoding="utf-8"))
    except Exception as error:
        print(f"PC_RUNTIME_SOURCE_LOCKS_FAILED\n- metadata: {error}", file=sys.stderr)
        return 1

    if box64.get("schemaVersion") != 1:
        failures.append("Box64 schemaVersion must be 1")
    if box64.get("version") != "0.4.4":
        failures.append("Box64 version must stay pinned to reviewed v0.4.4")
    if box64.get("tag") != "v0.4.4":
        failures.append("Box64 tag mismatch")
    if not SHA40.fullmatch(str(box64.get("commit") or "")):
        failures.append("Box64 commit must be a full 40-hex SHA")
    if box64.get("commit") != "2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a":
        failures.append("Box64 commit changed without lock review")
    if box64.get("license") != "MIT":
        failures.append("Box64 license evidence must remain MIT")
    for gate in ("compiled", "elfAudited", "rootfsIntegrated", "guestExecuted", "x86_64ProgramExecuted"):
        if (box64.get("gates") or {}).get(gate) is not False:
            failures.append(f"Box64 gate must remain false until evidence exists: {gate}")

    if wine.get("schemaVersion") != 1:
        failures.append("Wine schemaVersion must be 1")
    if wine.get("version") != "11.0":
        failures.append("Wine stable version must remain pinned to 11.0")
    if wine.get("tag") != "wine-11.0":
        failures.append("Wine tag mismatch")
    if wine.get("tagObject") != "ce295733f9a67970b7f60d7af201f2ac16441a50":
        failures.append("Wine annotated tag object changed without review")
    if wine.get("commit") != "db11d0fe6a169c457e23d007e20404643d067aa8":
        failures.append("Wine source commit changed without review")
    for gate in ("built", "prefixCreated", "win64LoaderExecuted", "notepadClassSmokeTest", "graphicsIntegrated", "robloxExecuted"):
        if (wine.get("gates") or {}).get(gate) is not False:
            failures.append(f"Wine gate must remain false until evidence exists: {gate}")

    if graphics.get("schemaVersion") != 1:
        failures.append("graphics schemaVersion must be 1")
    dxvk = graphics.get("dxvk") or {}
    if dxvk.get("version") != "3.0.2":
        failures.append("DXVK version must remain pinned to 3.0.2")
    if dxvk.get("tagObject") != "767633ab3481e4e30687bedaba982c1a5e4722a7":
        failures.append("DXVK signed tag object changed without review")
    if dxvk.get("commit") != "6b20f622a77b87b2921fe5d2c1774d2f2ba3e9b7":
        failures.append("DXVK source commit changed without review")
    for gate in ("built", "wineIntegrated", "vulkanGuestTested"):
        if dxvk.get(gate) is not False:
            failures.append(f"DXVK gate must remain false until evidence exists: {gate}")

    vkd3d = graphics.get("vkd3dProton") or {}
    if vkd3d.get("version") != "3.0.1":
        failures.append("vkd3d-proton version must remain pinned to 3.0.1")
    if vkd3d.get("commit") != "3b10bd7a7ec6a7347e616cf8bea59333afec2255":
        failures.append("vkd3d-proton source commit changed without review")
    for gate in ("built", "wineIntegrated", "vulkanGuestTested"):
        if vkd3d.get(gate) is not False:
            failures.append(f"vkd3d-proton gate must remain false until evidence exists: {gate}")

    for gate, value in (graphics.get("gates") or {}).items():
        if value is not False:
            failures.append(f"graphics runtime gate must remain false until evidence exists: {gate}")

    if failures:
        print("PC_RUNTIME_SOURCE_LOCKS_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    print("PC_RUNTIME_SOURCE_LOCKS_OK")
    print("box64=v0.4.4@2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a")
    print("wine=11.0@db11d0fe6a169c457e23d007e20404643d067aa8")
    print("dxvk=3.0.2@6b20f622a77b87b2921fe5d2c1774d2f2ba3e9b7")
    print("vkd3d-proton=3.0.1@3b10bd7a7ec6a7347e616cf8bea59333afec2255")
    print("runtime_execution_evidence=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
