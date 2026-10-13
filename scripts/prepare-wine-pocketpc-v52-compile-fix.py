#!/usr/bin/env python3
"""Normalize the prepared PocketPC Wine v52 source for compilation.

The v52 overlay introduces ``pocketpc_continuous_present_v52_enabled`` after a
queue callback that already calls it. In C this creates an implicit non-static
declaration before the later static definition and the real Wine build rejects
it. This stage inserts one matching static forward declaration before the first
use and otherwise leaves the v52 logic unchanged.

This is source normalization only. It does not compile or execute Wine, Vulkan,
Android presentation, or Roblox.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

ABI_52 = "#define WINE_VULKAN_DRIVER_VERSION 52"
QUEUE_CALLBACK = "static void pocketpc_vulkan_queue_presented("
GATE_DEFINITION = "static BOOL pocketpc_continuous_present_v52_enabled(void)\n{"
GATE_DECLARATION = "static BOOL pocketpc_continuous_present_v52_enabled(void);"


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wine-source", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()

    source = args.wine_source.resolve()
    header = source / "include/wine/vulkan_driver.h"
    driver = source / "dlls/winepocketpc.drv/vulkan.c"
    for required in (header, driver):
        if not required.is_file():
            raise SystemExit(f"WINE_V52_COMPILE_FIX_REQUIRED_FILE_MISSING:{required}")

    if ABI_52 not in header.read_text(encoding="utf-8"):
        raise SystemExit("WINE_V52_COMPILE_FIX_REQUIRES_ABI_52")

    text = driver.read_text(encoding="utf-8")
    before_hash = sha256_text(text)

    if text.count(QUEUE_CALLBACK) != 1:
        raise SystemExit(
            "WINE_V52_COMPILE_FIX_QUEUE_CALLBACK_COUNT=" +
            str(text.count(QUEUE_CALLBACK))
        )
    if text.count(GATE_DEFINITION) != 1:
        raise SystemExit(
            "WINE_V52_COMPILE_FIX_GATE_DEFINITION_COUNT=" +
            str(text.count(GATE_DEFINITION))
        )
    if text.count(GATE_DECLARATION) != 0:
        raise SystemExit(
            "WINE_V52_COMPILE_FIX_GATE_DECLARATION_ALREADY_PRESENT=" +
            str(text.count(GATE_DECLARATION))
        )

    queue_index = text.index(QUEUE_CALLBACK)
    definition_index = text.index(GATE_DEFINITION)
    if definition_index < queue_index:
        raise SystemExit("WINE_V52_COMPILE_FIX_GATE_ALREADY_DEFINED_BEFORE_USE")

    text = text.replace(
        QUEUE_CALLBACK,
        GATE_DECLARATION + "\n\n" + QUEUE_CALLBACK,
        1,
    )

    declaration_index = text.index(GATE_DECLARATION)
    queue_index = text.index(QUEUE_CALLBACK)
    definition_index = text.index(GATE_DEFINITION)
    if not (declaration_index < queue_index < definition_index):
        raise SystemExit("WINE_V52_COMPILE_FIX_DECLARATION_ORDER_FAILED")
    if text.count(GATE_DECLARATION) != 1 or text.count(GATE_DEFINITION) != 1:
        raise SystemExit("WINE_V52_COMPILE_FIX_GATE_CARDINALITY_FAILED")

    driver.write_text(text, encoding="utf-8")

    evidence = {
        "schemaVersion": 1,
        "status": "POCKETPC_WINE_V52_COMPILE_SOURCE_FIXED_NOT_BUILT_NOT_EXECUTED",
        "privateWineVulkanAbi": 52,
        "sourceIntegration": {
            "continuousPresentGateForwardDeclaredBeforeQueueCallback": True,
            "continuousPresentGateStaticDefinitionRetained": True,
            "declarationCount": 1,
            "definitionCount": 1,
        },
        "files": {
            "driverBeforeSha256": before_hash,
            "driverAfterSha256": sha256_text(text),
        },
        "compiled": False,
        "runtimeExecuted": False,
        "physicalVisibleFrame": False,
        "robloxExecuted": False,
        "notExecuted": [
            "Wine v52 compilation after normalization",
            "Wine v52 driver load",
            "continuous Present execution",
            "Android-visible frame",
            "Roblox Desktop",
        ],
    }
    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    args.evidence.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")

    print("POCKETPC_WINE_V52_COMPILE_SOURCE_FIXED_NOT_EXECUTED")
    print("continuous_present_gate_forward_declared=true")
    print("compiled=false")
    print("runtime_execution_evidence=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
