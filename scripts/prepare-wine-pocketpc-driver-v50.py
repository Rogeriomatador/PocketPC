#!/usr/bin/env python3
"""Prepare the PocketPC Wine driver through the exact-present-image v50 overlay.

This wrapper deliberately composes the existing v49 driver preparer with the
v50 exact-present-image preparer. It writes one fail-closed evidence document
that remains backward-compatible with build-wine-x86_64.py while recording the
private Wine Vulkan ABI 50 gates needed by PocketPC.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
BASE_PREPARER = ROOT / "scripts/prepare-wine-pocketpc-driver.py"
PRESENT_PREPARER = ROOT / "scripts/prepare-wine-pocketpc-present-image.py"


def run_stage(script: Path, wine_source: Path, evidence: Path) -> None:
    if not script.is_file():
        raise SystemExit(f"WINE_V50_PREPARER_MISSING:{script}")
    result = subprocess.run(
        [
            sys.executable,
            str(script),
            "--wine-source",
            str(wine_source),
            "--evidence",
            str(evidence),
        ],
        cwd=ROOT,
        text=True,
    )
    if result.returncode:
        raise SystemExit(
            f"WINE_V50_PREPARER_STAGE_FAILED:{script.name}:{result.returncode}"
        )


def require_base(base: dict[str, object]) -> None:
    required = {
        "protocolVersion": 4,
        "driverName": "winepocketpc.drv",
        "surfaceCallbackImplemented": True,
        "inputInjectionImplemented": True,
    }
    for key, expected in required.items():
        if base.get(key) != expected:
            raise SystemExit(
                f"WINE_V50_BASE_EVIDENCE_INVALID:{key}:"
                f"expected={expected!r}:actual={base.get(key)!r}"
            )


def require_present(present: dict[str, object]) -> None:
    if present.get("schemaVersion") != 3:
        raise SystemExit("WINE_V50_PRESENT_EVIDENCE_SCHEMA_INVALID")
    if present.get("privateWineVulkanAbi") != 50:
        raise SystemExit("WINE_V50_PRIVATE_ABI_NOT_50")

    exact = present.get("exactIdentity")
    ownership = present.get("externalOwnership")
    if not isinstance(exact, dict) or not isinstance(ownership, dict):
        raise SystemExit("WINE_V50_PRESENT_EVIDENCE_SHAPE_INVALID")

    exact_required = (
        "usesVkGetSwapchainImagesKHR",
        "usesVkPresentInfoImageIndex",
        "passesExactHostVkImage",
        "passesHostFormat",
        "passesHostExtent",
        "passesQueueFamily",
    )
    if any(exact.get(key) is not True for key in exact_required):
        raise SystemExit("WINE_V50_EXACT_IMAGE_IDENTITY_INCOMPLETE")

    if ownership.get("guestAcquireReleasePrimitiveImplemented") is not True:
        raise SystemExit("WINE_V50_GUEST_OWNERSHIP_PRIMITIVE_MISSING")
    if ownership.get("androidInitialReleaseImplemented") is not True:
        raise SystemExit("WINE_V50_ANDROID_INITIAL_RELEASE_MISSING")
    if ownership.get("guestPrimitiveActivated") is not True:
        raise SystemExit("WINE_V50_GUEST_OWNERSHIP_NOT_ACTIVATED")
    if ownership.get("boundaryLayout") != "VK_IMAGE_LAYOUT_GENERAL":
        raise SystemExit("WINE_V50_BOUNDARY_LAYOUT_INVALID")
    if ownership.get("externalQueueFamily") != "VK_QUEUE_FAMILY_EXTERNAL":
        raise SystemExit("WINE_V50_EXTERNAL_QUEUE_FAMILY_INVALID")
    if ownership.get("executed") is not False:
        raise SystemExit("WINE_V50_SOURCE_EVIDENCE_MUST_NOT_CLAIM_EXECUTION")

    if present.get("pixelCopyImplemented") is not False:
        raise SystemExit("WINE_V50_SOURCE_MUST_NOT_CLAIM_PIXEL_COPY")
    if present.get("hostVisiblePresentImplemented") is not False:
        raise SystemExit("WINE_V50_SOURCE_MUST_NOT_CLAIM_VISIBLE_PRESENT")
    if present.get("robloxExecuted") is not False:
        raise SystemExit("WINE_V50_SOURCE_MUST_NOT_CLAIM_ROBLOX")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wine-source", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()

    wine_source = args.wine_source.resolve()
    if not wine_source.is_dir():
        raise SystemExit(f"WINE_V50_SOURCE_MISSING:{wine_source}")

    with tempfile.TemporaryDirectory(prefix="pocketpc-wine-v50-") as temp:
        temp_root = Path(temp)
        base_path = temp_root / "base-v49.json"
        present_path = temp_root / "present-v50.json"

        run_stage(BASE_PREPARER, wine_source, base_path)
        base = json.loads(base_path.read_text(encoding="utf-8"))
        require_base(base)

        run_stage(PRESENT_PREPARER, wine_source, present_path)
        present = json.loads(present_path.read_text(encoding="utf-8"))
        require_present(present)

    combined = dict(base)
    combined.update(
        {
            "status": "POCKETPC_WINE_DRIVER_V50_SOURCE_INTEGRATED_NOT_BUILT_NOT_EXECUTED",
            "privateWineVulkanAbi": 50,
            "upstreamPinnedAbi": present.get("upstreamPinnedAbi"),
            "exactPresentedImage": present["exactIdentity"],
            "externalOwnership": present["externalOwnership"],
            "pixelCopyImplemented": False,
            "hostVisiblePresentImplemented": False,
            "robloxExecuted": False,
            "v50SourceEvidence": {
                "schemaVersion": present.get("schemaVersion"),
                "status": present.get("status"),
                "files": present.get("files"),
                "ownershipFiles": present.get("ownershipFiles"),
                "notExecuted": present.get("notExecuted"),
            },
        }
    )

    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    args.evidence.write_text(
        json.dumps(combined, indent=2) + "\n",
        encoding="utf-8",
    )

    print("POCKETPC_WINE_DRIVER_V50_SOURCE_INTEGRATED_NOT_EXECUTED")
    print("private_wine_vulkan_abi=50")
    print("exact_presented_image_identity=true")
    print("external_ownership_source_integrated=true")
    print("pixel_copy=false")
    print("host_visible_present=false")
    print("roblox_executed=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
