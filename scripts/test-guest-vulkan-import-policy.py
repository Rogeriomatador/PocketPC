#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
IMPORT_H = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_guest_vulkan_import.h"
IMPORT_C = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_guest_vulkan_import.c"
PVI_H = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_external_image_fd_protocol.h"
MAKEFILE = ROOT / "third_party/wine/pocketpc-driver/Makefile.in"
PREPARER = ROOT / "scripts/prepare-wine-pocketpc-driver.py"
CONTRACT = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GuestGraphicsTransportContract.kt"
PLANNER = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GuestGraphicsTransportPlanner.kt"


def require(failures: list[str], label: str, text: str, markers: tuple[str, ...]) -> None:
    for marker in markers:
        if marker not in text:
            failures.append(f"{label} missing: {marker}")


def main() -> int:
    failures: list[str] = []
    for path in (IMPORT_H, IMPORT_C, PVI_H, MAKEFILE, PREPARER, CONTRACT, PLANNER):
        if not path.is_file():
            failures.append(f"missing: {path.relative_to(ROOT)}")
    if failures:
        print("GUEST_VULKAN_IMPORT_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    header = IMPORT_H.read_text(encoding="utf-8")
    source = IMPORT_C.read_text(encoding="utf-8")
    pvi = PVI_H.read_text(encoding="utf-8")
    makefile = MAKEFILE.read_text(encoding="utf-8")
    preparer = PREPARER.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")
    planner = PLANNER.read_text(encoding="utf-8")

    require(
        failures,
        "import API",
        header,
        (
            "struct pocketpc_guest_vulkan_image",
            "pocketpc_guest_vulkan_import_external_image(",
            "struct vulkan_device *device",
            "struct pocketpc_external_image_fd_received *received",
            "pocketpc_guest_vulkan_import_release(",
            "It does not establish semaphore/fence",
        ),
    )
    require(
        failures,
        "import implementation",
        source,
        (
            "device->extensions.has_VK_KHR_external_memory_fd",
            "device->p_vkGetMemoryFdPropertiesKHR",
            "VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT",
            "VkExternalMemoryImageCreateInfo",
            "VkImportMemoryFdInfoKHR",
            "VkMemoryDedicatedAllocateInfo",
            "requirements.memoryTypeBits &",
            "fd_properties.memoryTypeBits &",
            "received->metadata.memory_type_bits",
            "device->physical_device",
            "received->resource_fd = -1;",
            "device->p_vkBindImageMemory",
        ),
    )
    if "received->metadata.memory_type_index" in source:
        failures.append(
            "guest import must not trust host memory_type_index as allocation authority"
        )

    require(
        failures,
        "PVI1 metadata",
        pvi,
        (
            "uint64_t allocation_size;",
            "uint32_t memory_type_bits;",
            "uint32_t memory_type_index;",
        ),
    )
    require(
        failures,
        "Wine compilation",
        makefile,
        ("\tpocketpc_guest_vulkan_import.c \\",),
    )
    require(
        failures,
        "Wine overlay",
        preparer,
        (
            '"pocketpc_guest_vulkan_import.h",',
            '"pocketpc_guest_vulkan_import.c",',
            '"guestVulkanImportPrimitiveImplemented": True',
            '"guestGraphicsImportIntegrated": False',
        ),
    )
    require(
        failures,
        "runtime contract",
        contract,
        (
            "const val guestVulkanImportPrimitiveImplemented = true",
            "const val guestImportImplemented = false",
            "const val synchronizationImplemented = false",
        ),
    )
    require(
        failures,
        "route planner",
        planner,
        (
            "GuestGraphicsTransportContract.guestVulkanImportPrimitiveImplemented",
            "GUEST_GRAPHICS_RESOURCE_IMPORT_NOT_IMPLEMENTED",
            "GUEST_GRAPHICS_SYNCHRONIZATION_NOT_IMPLEMENTED",
        ),
    )

    if failures:
        print("GUEST_VULKAN_IMPORT_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    print("GUEST_VULKAN_IMPORT_POLICY_OK")
    print("import_primitive_implemented=true")
    print("runtime_import_integrated=false")
    print("gpu_synchronization_implemented=false")
    print("visible_present_implemented=false")
    print("roblox_executed=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
