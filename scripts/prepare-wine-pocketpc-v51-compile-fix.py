#!/usr/bin/env python3
"""Normalize the prepared PocketPC Wine v51 source for compilation.

This stage runs only after the v51 pre-Present copy and PGA1 stage-6 patches.
It fixes two source-generation issues observed by the real Wine 11 build:

* stage-6 reads the copy-completed state before the generated declaration;
* the PGT1 descriptor field is named ``pixel_format``, not ``format``.

The transformation is exact and fail-closed. It does not execute Wine, Vulkan,
Android presentation, or Roblox and must never be treated as runtime evidence.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

ABI_51 = "#define WINE_VULKAN_DRIVER_VERSION 51"
QUEUE_CALLBACK = "static void pocketpc_vulkan_queue_presented("
COPY_COMPLETED_DECL = "static LONG pocketpc_pre_present_copy_completed;"
COPY_ACK_DECL = "static LONG pocketpc_pre_present_copy_ack_sent;"
BAD_FORMAT = "pocketpc_guest_resource.descriptor.format"
GOOD_FORMAT = "pocketpc_guest_resource.descriptor.pixel_format"


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wine-source", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()

    source = args.wine_source.resolve()
    header = source / "include/wine/vulkan_driver.h"
    driver = source / "dlls/winepocketpc.drv/vulkan.c"
    transport = source / "dlls/winepocketpc.drv/pocketpc_graphics_transport.h"

    for required in (header, driver, transport):
        if not required.is_file():
            raise SystemExit(f"WINE_V51_COMPILE_FIX_REQUIRED_FILE_MISSING:{required}")

    if ABI_51 not in header.read_text(encoding="utf-8"):
        raise SystemExit("WINE_V51_COMPILE_FIX_REQUIRES_ABI_51")

    transport_text = transport.read_text(encoding="utf-8")
    if "uint32_t pixel_format;" not in transport_text:
        raise SystemExit("WINE_V51_COMPILE_FIX_PIXEL_FORMAT_CONTRACT_MISSING")
    if "uint32_t format;" in transport_text:
        raise SystemExit("WINE_V51_COMPILE_FIX_AMBIGUOUS_FORMAT_CONTRACT")

    text = driver.read_text(encoding="utf-8")
    before_hash = hashlib.sha256(text.encode("utf-8")).hexdigest()

    if text.count(QUEUE_CALLBACK) != 1:
        raise SystemExit(
            "WINE_V51_COMPILE_FIX_QUEUE_CALLBACK_COUNT=" +
            str(text.count(QUEUE_CALLBACK))
        )
    if text.count(COPY_COMPLETED_DECL) != 1:
        raise SystemExit(
            "WINE_V51_COMPILE_FIX_COMPLETED_DECL_COUNT=" +
            str(text.count(COPY_COMPLETED_DECL))
        )
    if text.count(COPY_ACK_DECL) != 1:
        raise SystemExit(
            "WINE_V51_COMPILE_FIX_ACK_DECL_COUNT=" +
            str(text.count(COPY_ACK_DECL))
        )
    if text.count(BAD_FORMAT) != 2:
        raise SystemExit(
            "WINE_V51_COMPILE_FIX_BAD_FORMAT_COUNT=" +
            str(text.count(BAD_FORMAT))
        )

    queue_index = text.index(QUEUE_CALLBACK)
    if text.index(COPY_COMPLETED_DECL) < queue_index or text.index(COPY_ACK_DECL) < queue_index:
        raise SystemExit("WINE_V51_COMPILE_FIX_DECLARATIONS_ALREADY_MOVED_UNEXPECTEDLY")

    # Move both state declarations before the queue callback that emits PGA1
    # stages 5/6. Their storage duration and zero-initialization remain static.
    text = text.replace(COPY_COMPLETED_DECL + "\n", "", 1)
    text = text.replace(COPY_ACK_DECL + "\n", "", 1)
    early_declarations = COPY_COMPLETED_DECL + "\n" + COPY_ACK_DECL + "\n\n"
    text = text.replace(QUEUE_CALLBACK, early_declarations + QUEUE_CALLBACK, 1)

    # PGT1 carries the canonical pixel format in pixel_format. The current PVI1
    # contract uses the same numeric VkFormat identity (R8G8B8A8_UNORM = 37).
    text = text.replace(BAD_FORMAT, GOOD_FORMAT)

    if text.count(BAD_FORMAT) != 0 or text.count(GOOD_FORMAT) < 2:
        raise SystemExit("WINE_V51_COMPILE_FIX_FORMAT_REWRITE_FAILED")
    queue_index = text.index(QUEUE_CALLBACK)
    if text.index(COPY_COMPLETED_DECL) > queue_index or text.index(COPY_ACK_DECL) > queue_index:
        raise SystemExit("WINE_V51_COMPILE_FIX_DECLARATION_ORDER_FAILED")

    driver.write_text(text, encoding="utf-8")

    evidence = {
        "schemaVersion": 1,
        "status": "POCKETPC_WINE_V51_COMPILE_SOURCE_FIXED_NOT_BUILT_NOT_EXECUTED",
        "privateWineVulkanAbi": 51,
        "sourceIntegration": {
            "copyCompletedDeclarationMovedBeforeQueueCallback": True,
            "copyAckDeclarationMovedBeforeQueueCallback": True,
            "descriptorPixelFormatContractUsed": True,
            "descriptorFormatReferencesRewritten": 2,
        },
        "files": {
            "driverBeforeSha256": before_hash,
            "driverAfterSha256": digest(driver),
            "transportSha256": digest(transport),
        },
        "compiled": False,
        "runtimeExecuted": False,
        "androidVisibleFrame": False,
        "robloxExecuted": False,
        "notExecuted": [
            "Wine v51 compilation after normalization",
            "Wine driver load",
            "pre-Present GPU copy execution",
            "PGA1 stage 5 send",
            "PGA1 stage 6 send",
            "Android-visible frame",
            "Roblox Desktop",
        ],
    }
    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    args.evidence.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")

    print("POCKETPC_WINE_V51_COMPILE_SOURCE_FIXED_NOT_EXECUTED")
    print("descriptor_pixel_format_contract=true")
    print("declarations_before_queue_callback=true")
    print("compiled=false")
    print("runtime_execution_evidence=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
