#!/usr/bin/env python3
"""Static policy lock for the Wine x86_64 v50 package composition."""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def text(path: str) -> str:
    candidate = ROOT / path
    if not candidate.is_file():
        raise SystemExit(f"WINE_V50_INTEGRATION_FILE_MISSING:{path}")
    return candidate.read_text(encoding="utf-8")


def require(source: str, needle: str, label: str) -> None:
    if needle not in source:
        raise SystemExit(f"WINE_V50_INTEGRATION_MISSING:{label}")


def main() -> int:
    preparer = text("scripts/prepare-wine-pocketpc-driver-v50.py")
    build = text("scripts/build-wine-x86_64-v50.py")
    workflow = text(".github/workflows/wine-x86_64-build.yml")

    require(preparer, "prepare-wine-pocketpc-driver.py", "base_preparer")
    require(preparer, "prepare-wine-pocketpc-present-image.py", "present_preparer")
    require(preparer, '"privateWineVulkanAbi": 50', "abi_50_evidence")
    require(preparer, '"pixelCopyImplemented": False', "pixel_copy_fail_closed")
    require(preparer, '"hostVisiblePresentImplemented": False', "visible_present_fail_closed")
    require(preparer, '"robloxExecuted": False', "roblox_fail_closed")
    require(preparer, 'ownership.get("executed") is not False', "ownership_execution_guard")

    require(build, "build-wine-x86_64.py", "base_build")
    require(build, "module.DRIVER_PREPARER = V50_PREPARER", "preparer_override")
    require(workflow, "python3 scripts/build-wine-x86_64-v50.py", "workflow_v50_build")
    require(workflow, "python3 scripts/test-wine-x86_64-v50-integration.py", "workflow_policy")
    require(workflow, "wine-pocketpc-driver-overlay-evidence.json", "overlay_evidence_retained")

    print("WINE_X86_64_V50_INTEGRATION_POLICY_OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
