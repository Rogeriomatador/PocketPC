#!/usr/bin/env python3
"""Build the pinned Wine x86_64 guest package with the experimental PocketPC Vulkan ABI v52.

This wrapper deliberately reuses the existing pinned Wine builder but swaps only
the source preparer. It is experimental and MUST NOT be selected by the official
Wine workflow until v52 has independent build, integration, and physical proof.
"""
from __future__ import annotations

import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE_BUILD = ROOT / "scripts/build-wine-x86_64.py"
V52_PREPARER = ROOT / "scripts/prepare-wine-pocketpc-driver-v52.py"


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


def main() -> int:
    if not BASE_BUILD.is_file():
        raise SystemExit(f"WINE_V52_BASE_BUILD_MISSING:{BASE_BUILD}")
    if not V52_PREPARER.is_file():
        raise SystemExit(f"WINE_V52_PREPARER_MISSING:{V52_PREPARER}")

    module = load_base_build()
    module.DRIVER_PREPARER = V52_PREPARER
    result = module.main()
    return int(result or 0)


if __name__ == "__main__":
    raise SystemExit(main())
