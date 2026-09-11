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
PVI_HEADER = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_external_image_fd_protocol.h"
PVI_SOURCE = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_external_image_fd_protocol.c"
OWNERSHIP_HEADER = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_guest_external_image_ownership.h"
OWNERSHIP_SOURCE = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_guest_external_image_ownership.c"
MAKEFILE = ROOT / "third_party/wine/pocketpc-driver/Makefile.in"
PREPARER = ROOT / "scripts/prepare-wine-pocketpc-driver.py"
PRESENT_PREPARER = ROOT / "scripts/prepare-wine-pocketpc-present-image.py"


def require(failures: list[str], label: str, text: str, markers: tuple[str, ...]) -> None:
    for marker in markers:
        if marker not in text:
            failures.append(f"{label} missing: {marker}")


def main() -> int:
    failures: list[str] = []
    paths = (
        NATIVE, KOTLIN, TEST, CMAKE, CONTRACT, PLANNER,
        PVI_HEADER, PVI_SOURCE, OWNERSHIP_HEADER, OWNERSHIP_SOURCE,
        MAKEFILE, PREPARER, PRESENT_PREPARER,
    )
    for path in paths:
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
    pvi_header = PVI_HEADER.read_text(encoding="utf-8")
    pvi_source = PVI_SOURCE.read_text(encoding="utf-8")
    ownership_header = OWNERSHIP_HEADER.read_text(encoding="utf-8")
    ownership_source = OWNERSHIP_SOURCE.read_text(encoding="utf-8")
    makefile = MAKEFILE.read_text(encoding="utf-8")
    preparer = PREPARER.read_text(encoding="utf-8")
    present_preparer = PRESENT_PREPARER.read_text(encoding="utf-8")

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
            "kExternalImageMagic = 0x31495650u",
            "kExternalImageVersion = 1",
            "kExternalImagePayloadBytes = 64",
            "SCM_RIGHTS",
            "SOCK_SEQPACKET",
            "close(exported_fd);",
            "stale-or-unknown-resource",
            "VkQueue queue = VK_NULL_HANDLE",
            "uint32_t queue_family = UINT32_MAX",
            "VkCommandPool command_pool = VK_NULL_HANDLE",
            "kExternalBoundaryLayout = VK_IMAGE_LAYOUT_GENERAL",
            "ReleaseImageToExternal",
            "VK_QUEUE_FAMILY_EXTERNAL",
            "vkCmdPipelineBarrier",
            "vkQueueSubmit",
            "vkQueueWaitIdle",
            "released_to_external = true",
            "external-release-not-ready",
            '";external_owner="',
            '";boundary_layout="',
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
            "val externalOwner: Boolean",
            "val boundaryLayout: Int",
            "const val FORMAT_R8G8B8A8_UNORM = 37",
            "const val IMAGE_USAGE_FLAGS = 0x17L",
            "const val EXTERNAL_BOUNDARY_LAYOUT_GENERAL = 1",
            "externalOwner &&",
            "boundaryLayout == VulkanExternalImageFdBroker.EXTERNAL_BOUNDARY_LAYOUT_GENERAL",
            'fields["external_owner"] == "1"',
            "usage = VulkanExternalImageFdBroker.IMAGE_USAGE_FLAGS",
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
            "wrongFormatCannotBecomeLease",
            "leaseRequiresExternalOwnershipAndGeneralBoundaryLayout",
            "duplicateUnknownOrMissingFieldsAreRejected",
            "leaseContainsNoProcessLocalFileDescriptor",
            "external_owner=1",
            "boundary_layout=1",
            "VulkanExternalImageFdBroker.IMAGE_USAGE_FLAGS",
        ),
    )

    require(
        failures,
        "PVI1 header",
        pvi_header,
        (
            "POCKETPC_EXTERNAL_IMAGE_FD_MAGIC 0x31495650u",
            "POCKETPC_EXTERNAL_IMAGE_FD_VERSION 1u",
            "POCKETPC_EXTERNAL_IMAGE_FD_PAYLOAD_BYTES 64u",
            "POCKETPC_EXTERNAL_IMAGE_FORMAT_R8G8B8A8_UNORM 37u",
            "POCKETPC_EXTERNAL_IMAGE_USAGE_FLAGS 0x17u",
            "struct pocketpc_external_image_fd_metadata",
            "struct pocketpc_external_image_fd_received",
            "pocketpc_external_image_fd_receive(",
            "pocketpc_external_image_fd_received_release(",
        ),
    )
    require(
        failures,
        "PVI1 receiver",
        pvi_source,
        (
            "recvmsg(",
            "SCM_RIGHTS",
            "SOCK_SEQPACKET",
            "MSG_CTRUNC",
            "MSG_TRUNC",
            "pvi_set_cloexec",
            "pocketpc_graphics_handle_binding_validate_offer",
            "metadata->allocation_size == 0u",
            "metadata->memory_type_bits == 0u",
            "descriptor->usage == (uint64_t)metadata->usage",
        ),
    )

    require(
        failures,
        "guest ownership header",
        ownership_header,
        (
            "POCKETPC_EXTERNAL_IMAGE_BOUNDARY_LAYOUT VK_IMAGE_LAYOUT_GENERAL",
            "pocketpc_guest_external_image_acquire(",
            "pocketpc_guest_external_image_release(",
            "VK_QUEUE_FAMILY_EXTERNAL",
        ),
    )
    require(
        failures,
        "guest ownership source",
        ownership_source,
        (
            "VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER",
            "p_vkCmdPipelineBarrier",
            "p_vkQueueSubmit",
            "p_vkQueueWaitIdle",
            "VK_QUEUE_FAMILY_EXTERNAL",
            "queue->info.queueFamilyIndex",
            "VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL",
            "POCKETPC_EXTERNAL_IMAGE_BOUNDARY_LAYOUT",
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
        "Wine build integration",
        makefile,
        (
            "\tpocketpc_external_image_fd_protocol.c \\",
            "\tpocketpc_guest_external_image_ownership.c \\",
        ),
    )
    require(
        failures,
        "Wine base overlay integration",
        preparer,
        (
            '"pocketpc_external_image_fd_protocol.h",',
            '"pocketpc_external_image_fd_protocol.c",',
            '"externalImageFdProtocol": "PVI1"',
            '"externalImagePvi1ProtocolImplemented": True',
        ),
    )
    require(
        failures,
        "Wine v50 ownership carry integration",
        present_preparer,
        (
            '"pocketpc_guest_external_image_ownership.h"',
            '"pocketpc_guest_external_image_ownership.c"',
            '"guestAcquireReleasePrimitiveImplemented": True',
            '"androidInitialReleaseImplemented": True',
            '"guestPrimitiveActivated": True',
            '"roundTripSourceIntegrated": True',
            "pocketpc_guest_external_image_acquire(",
            "pocketpc_guest_external_image_release(",
            "stage=roundtrip_completed",
        ),
    )

    require(
        failures,
        "runtime contract",
        contract,
        (
            "const val hostOpaqueFdImageBrokerImplemented =\n        true",
            "const val hostOpaqueFdInitialExternalReleaseSourceIntegrated = true",
            "const val guestExternalImageAcquireReleasePrimitiveImplemented = true",
            "const val hostOpaqueFdInitialExternalReleaseExecuted = false",
            "const val guestExternalImageAcquireExecuted = false",
            "const val guestExternalImageReleaseExecuted = false",
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
    print("external_image_fd_protocol=PVI1")
    print("host_opaque_fd_image_broker_implemented=true")
    print("host_initial_external_queue_release_source_integrated=true")
    print("guest_external_queue_acquire_release_primitive=true")
    print("guest_external_queue_roundtrip_source_integrated=true")
    print("external_queue_roundtrip_executed=false")
    print("host_dma_buf_image_broker_implemented=false")
    print("guest_receive_runtime_integrated=false")
    print("guest_vulkan_import_implemented=false")
    print("visible_present_implemented=false")
    print("execution_evidence=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
