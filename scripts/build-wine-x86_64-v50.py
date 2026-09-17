#!/usr/bin/env python3
"""Build the pinned Wine x86_64 guest package with PocketPC Vulkan ABI v50.

The canonical build implementation remains build-wine-x86_64.py. This wrapper
only replaces its driver-preparer hook with the fail-closed v50 composite
preparer, so packaging, deterministic hashes and guest-tool manifest generation
continue to use the existing audited path.
"""
from __future__ import annotations

import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE_BUILD = ROOT / "scripts/build-wine-x86_64.py"
V50_PREPARER = ROOT / "scripts/prepare-wine-pocketpc-driver-v50.py"


def load_base_build():
    spec = importlib.util.spec_from_file_location(
        "pocketpc_build_wine_x86_64_base",
        BASE_BUILD,
    )
    if spec is None or spec.loader is None:
        raise SystemExit("WINE_V50_BASE_BUILD_IMPORT_FAILED")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> int:
    if not BASE_BUILD.is_file():
        raise SystemExit(f"WINE_V50_BASE_BUILD_MISSING:{BASE_BUILD}")
    if not V50_PREPARER.is_file():
        raise SystemExit(f"WINE_V50_PREPARER_MISSING:{V50_PREPARER}")

    module = load_base_build()
    module.DRIVER_PREPARER = V50_PREPARER

    result = module.main()
    return int(result or 0)


if __name__ == "__main__":
    raise SystemExit(main())
