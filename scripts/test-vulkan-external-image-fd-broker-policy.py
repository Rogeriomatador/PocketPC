#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "app/src/main/cpp/vulkan_external_image_fd_broker.cpp"
KOTLIN = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/VulkanExternalImageFdBroker.kt"
TEST = ROOT / "app/src/test/java/dev/pocketpc/core/runtime/VulkanExternalImageFdBrokerTest.kt"
CMAKE = ROOT / "app/src/main/cpp/CMakeLists.txt"
CONTRACT = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GuestGraphicsTransportContract.kt"
PLANNER = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GuestGraphicsTransportPlanner.kt"


def require(failures: list[str], label: str, text: str, markers: tuple[str, ...]) -> None:
    for marker in markers:
        if marker not in text:
            failures.append(f"{label} missing: {marker}")


def main() -> int:
    failures: list[str] = []
    for path in (NATIVE, KOTLIN, TEST, CMAKE, CONTRACT, PLANNER):
        if not path.is_file():
            failures.append(f"missing: {path.relative_to(ROOT)}")
    if failures:
        print("VULKAN_EXTERNAL_IMAGE_FD_BROKER_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    native = NATIVE.read_text(encoding="utf-8")
    kotlin = KOTLIN.read_text(encoding="utf-8")
    test = TEST.read_text(encoding="utf-8")
    cmake = CMAKE.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")
    planner = PLANNER.read_text(encoding="utf-8")

    require(
        failures,
        "native broker",
        native,
        (
            "VK_API_VERSION_1_1",
            "VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME",
            "VkPhysicalDeviceExternalImageFormatInfo",
            "VkExternalImageFormatProperties",
            "compatibleHandleTypes",
            "VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT",
            "VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT",
            "VkExternalMemoryImageCreateInfo",
            "VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT",
            "VkMemoryDedicatedAllocateInfo",
            "VkExportMemoryAllocateInfo",
            "vkAllocateMemory",
            "vkBindImageMemory",
            "vkGetMemoryFdKHR",
            "SCM_RIGHTS",
            "SOCK_SEQPACKET",
            "close(exported_fd);",
            "stale-or-unknown-resource",
        ),
    )
    require(
        failures,
        "native identity token",
        native,
        (
            "kFdTokenMagic = 0x31444650u",
            "kFdTokenVersion = 1",
            "PutU64Le(out + 8, resource_id)",
            "PutU64Le(out + 16, generation)",
            "PutU64Le(out + 24, sequence)",
        ),
    )

    require(
        failures,
        "typed Kotlin lease",
        kotlin,
        (
            "data class VulkanExternalImageFdLease",
            "val resourceId: Long",
            "val generation: Long",
            "val allocationSize: Long",
            "val memoryTypeBits: Long",
            "val memoryTypeIndex: Int",
            "fun toGuestDescriptor(",
            "private external fun nativeSend(",
            "internal fun parseLease(",
        ),
    )
    for forbidden in (
        "val fd:",
        "val fileDescriptor:",
        "ParcelFileDescriptor",
    ):
        if forbidden in kotlin:
            failures.append("Kotlin broker exposes process-local fd: " + forbidden)

    require(
        failures,
        "lease tests",
        test,
        (
            "memoryTypeIndexMustBeIncludedInMemoryTypeBits",
            "duplicateUnknownOrMissingFieldsAreRejected",
            "leaseContainsNoProcessLocalFileDescriptor",
        ),
    )
    require(
        failures,
        "native CMake integration",
        cmake,
        ("vulkan_external_image_fd_broker.cpp",),
    )
    require(
        failures,
        "runtime contract",
        contract,
        (
            "const val hostOpaqueFdImageBrokerImplemented =\n        true",
            "const val hostDmaBufImageBrokerImplemented =\n        false",
            "const val guestReceiveImplemented =\n        false",
            "const val guestImportImplemented =\n        false",
            "const val synchronizationImplemented =\n        false",
        ),
    )
    require(
        failures,
        "route planner",
        planner,
        (
            ".hostOpaqueFdImageBrokerImplemented",
            ".hostDmaBufImageBrokerImplemented",
            "BLOCKER_HOST_DMA_BUF_BROKER",
            "GUEST_GRAPHICS_RESOURCE_IMPORT_NOT_IMPLEMENTED",
        ),
    )

    if failures:
        print("VULKAN_EXTERNAL_IMAGE_FD_BROKER_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    print("VULKAN_EXTERNAL_IMAGE_FD_BROKER_POLICY_OK")
    print("host_opaque_fd_image_broker_implemented=true")
    print("host_dma_buf_image_broker_implemented=false")
    print("guest_receive_runtime_integrated=false")
    print("guest_vulkan_import_implemented=false")
    print("visible_present_implemented=false")
    print("execution_evidence=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
