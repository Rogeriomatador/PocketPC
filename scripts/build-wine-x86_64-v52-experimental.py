#!/usr/bin/env python3
"""Build the pinned Wine x86_64 guest package with experimental PocketPC Vulkan ABI v52.

The official PocketPC Wine build remains v51. This wrapper reuses the existing
pinned builder, swaps only the source preparer to v52, and emits a separate
post-build evidence record. Reaching that record proves package compilation
completed; it does not prove guest runtime, integration, physical visibility,
or Roblox.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
BASE_BUILD = ROOT / "scripts/build-wine-x86_64.py"
V52_PREPARER = ROOT / "scripts/prepare-wine-pocketpc-driver-v52.py"
EVIDENCE_NAME = "wine-v52-experimental-build-evidence.json"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def requested_work_dir() -> Path:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--work", type=Path, required=True)
    args, _ = parser.parse_known_args(sys.argv[1:])
    return args.work.resolve()


def load_base_build():
    spec = importlib.util.spec_from_file_location(
        "pocketpc_build_wine_x86_64_base_v52_experimental",
        BASE_BUILD,
    )
    if spec is None or spec.loader is None:
        raise SystemExit("WINE_V52_BASE_BUILD_IMPORT_FAILED")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def require_bool_false(mapping: dict[str, object], key: str, label: str) -> None:
    if mapping.get(key) is not False:
        raise SystemExit(f"WINE_V52_EVIDENCE_NOT_FAIL_CLOSED:{label}:{key}")


def emit_post_build_evidence(work: Path) -> None:
    base_path = work / "wine-build-evidence.json"
    overlay_path = work / "wine-pocketpc-driver-overlay-evidence.json"
    package_path = work / "guest-package.zip"
    for path in (base_path, overlay_path, package_path):
        if not path.is_file():
            raise SystemExit(f"WINE_V52_POST_BUILD_EVIDENCE_MISSING:{path.name}")

    base = json.loads(base_path.read_text(encoding="utf-8"))
    overlay = json.loads(overlay_path.read_text(encoding="utf-8"))

    if base.get("status") != "WINE_X86_64_WITH_POCKETPC_DRIVER_COMPILED_PACKAGE_NOT_GUEST_TESTED_NOT_APPROVED":
        raise SystemExit("WINE_V52_BASE_BUILD_STATUS_MISMATCH")
    if overlay.get("privateWineVulkanAbi") != 52:
        raise SystemExit("WINE_V52_OVERLAY_ABI_MISMATCH")
    if overlay.get("derivedFromPrivateAbi") != 51:
        raise SystemExit("WINE_V52_OVERLAY_BASE_ABI_MISMATCH")
    require_bool_false(overlay, "officialBuildSelected", "overlay")
    require_bool_false(overlay, "runtimeExecuted", "overlay")
    require_bool_false(overlay, "integrationExecuted", "overlay")
    require_bool_false(overlay, "physicalVisibleFrame", "overlay")
    require_bool_false(overlay, "robloxExecuted", "overlay")
    require_bool_false(overlay, "robloxRendered", "overlay")
    require_bool_false(overlay, "robloxPlayable", "overlay")

    evidence = {
        "schemaVersion": 1,
        "status": "EXPERIMENTAL_WINE_V52_COMPILED_PACKAGE_NOT_RUNTIME_TESTED_NOT_APPROVED",
        "targetPrivateWineVulkanAbi": 52,
        "derivedFromPrivateWineVulkanAbi": 51,
        "officialBuildSelected": False,
        "buildExecuted": True,
        "packageCompiled": True,
        "baseBuildEvidenceStatus": base["status"],
        "sourceCommit": base.get("sourceCommit"),
        "package": {
            "path": package_path.name,
            "bytes": package_path.stat().st_size,
            "sha256": sha256(package_path),
        },
        "evidenceInputs": {
            "wineBuildEvidenceSha256": sha256(base_path),
            "v52OverlayEvidenceSha256": sha256(overlay_path),
        },
        "runtimeExecuted": False,
        "integrationExecuted": False,
        "physicalVisibleFrame": False,
        "robloxExecuted": False,
        "robloxRendered": False,
        "robloxPlayable": False,
        "classification": {
            "build": "SOFTWARE_BUILD_EXECUTED",
            "runtime": "NOT_EXECUTED",
            "integration": "NOT_EXECUTED",
            "physical": "NOT_EXECUTED",
            "roblox": "NOT_EXECUTED",
        },
        "limitations": [
            "A successful Wine package build does not prove the v52 guest was loaded.",
            "No continuous-present frame is claimed from build evidence.",
            "No Android integration or physical-visible frame is claimed.",
            "No Roblox execution, rendering, playability, input, audio, network, or stability is claimed.",
        ],
    }
    output = work / EVIDENCE_NAME
    output.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(f"POCKETPC_WINE_V52_EXPERIMENTAL_BUILD_EVIDENCE={output}")
    print("V52_BUILD_EXECUTED=1")
    print("V52_RUNTIME_EXECUTED=0")
    print("V52_PHYSICAL_VISIBLE_FRAME=0")
    print("V52_ROBLOX_EXECUTED=0")


def main() -> int:
    if not BASE_BUILD.is_file():
        raise SystemExit(f"WINE_V52_BASE_BUILD_MISSING:{BASE_BUILD}")
    if not V52_PREPARER.is_file():
        raise SystemExit(f"WINE_V52_PREPARER_MISSING:{V52_PREPARER}")

    work = requested_work_dir()
    module = load_base_build()
    module.DRIVER_PREPARER = V52_PREPARER
    result = int(module.main() or 0)
    if result != 0:
        return result
    emit_post_build_evidence(work)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
