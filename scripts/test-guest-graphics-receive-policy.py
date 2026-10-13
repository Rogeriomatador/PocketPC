#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / "third_party/wine/pocketpc-display-bridge"
HEADER = BRIDGE / "pocketpc_guest_graphics_receive.h"
SOURCE = BRIDGE / "pocketpc_guest_graphics_receive.c"
SMOKE = BRIDGE / "guest_graphics_receive_smoke.c"
FD_TRANSPORT = BRIDGE / "pocketpc_fd_transport.c"
BINDING = BRIDGE / "pocketpc_graphics_handle_binding.c"
MAKEFILE = ROOT / "third_party/wine/pocketpc-driver/Makefile.in"
PREPARER = ROOT / "scripts/prepare-wine-pocketpc-driver.py"
RUNNER = ROOT / "scripts/run-pocketpc-guest-graphics-receive-smoke.py"
CONTRACT = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GuestGraphicsTransportContract.kt"


def require(failures: list[str], label: str, text: str, markers: tuple[str, ...]) -> None:
    for marker in markers:
        if marker not in text:
            failures.append(f"{label} missing: {marker}")


def main() -> int:
    failures: list[str] = []
    paths = (
        HEADER,
        SOURCE,
        SMOKE,
        FD_TRANSPORT,
        BINDING,
        MAKEFILE,
        PREPARER,
        RUNNER,
        CONTRACT,
    )
    for path in paths:
        if not path.is_file():
            failures.append(f"missing: {path.relative_to(ROOT)}")

    if failures:
        print("GUEST_GRAPHICS_RECEIVE_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    header = HEADER.read_text(encoding="utf-8")
    source = SOURCE.read_text(encoding="utf-8")
    smoke = SMOKE.read_text(encoding="utf-8")
    fd_transport = FD_TRANSPORT.read_text(encoding="utf-8")
    binding = BINDING.read_text(encoding="utf-8")
    makefile = MAKEFILE.read_text(encoding="utf-8")
    preparer = PREPARER.read_text(encoding="utf-8")
    runner = RUNNER.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")

    require(
        failures,
        "receive header",
        header,
        (
            "struct pocketpc_guest_graphics_received_offer",
            "int resource_fd;",
            "pocketpc_guest_graphics_receive_offer(",
            "pocketpc_guest_graphics_received_offer_release(",
            "It does not import the descriptor into Vulkan",
        ),
    )
    require(
        failures,
        "receive implementation",
        source,
        (
            "pocketpc_fd_transport_receive(",
            "pocketpc_graphics_handle_binding_validate_offer(",
            "close(resource_fd);",
            "received->resource_fd = resource_fd;",
            "received->resource_fd = -1;",
        ),
    )
    require(
        failures,
        "fd primitive",
        fd_transport,
        (
            "SCM_RIGHTS",
            "SOCK_SEQPACKET",
            "FD_CLOEXEC",
        ),
    )
    require(
        failures,
        "identity binding",
        binding,
        (
            "descriptor->resource_id != ownership->resource_id",
            "descriptor->generation != ownership->generation",
            "descriptor->sync_sequence != ownership->sequence",
            "PGT_STATE_OFFERED_TO_GUEST",
        ),
    )
    require(
        failures,
        "receive smoke",
        smoke,
        (
            "GUEST_GRAPHICS_RECEIVE_SMOKE_OK",
            "rejected-fd-leaked-to-caller",
            "generation-mismatch-accepted",
            "vulkan_import_executed=false",
            "gpu_synchronization_executed=false",
            "roblox_executed=false",
        ),
    )
    require(
        failures,
        "receive runner",
        runner,
        (
            '"-Wall"',
            '"-Wextra"',
            '"-Werror"',
            '"-Wpedantic"',
            '"classification": "SOFTWARE_TEST"',
            '"authenticatedRuntimeSessionExecuted": False',
            '"vulkanImportExecuted": False',
            '"gpuSynchronizationExecuted": False',
            '"robloxExecuted": False',
        ),
    )
    require(
        failures,
        "Wine build integration",
        makefile,
        ("\tpocketpc_guest_graphics_receive.c \\",),
    )
    require(
        failures,
        "Wine overlay integration",
        preparer,
        (
            '"pocketpc_guest_graphics_receive.h",',
            '"pocketpc_guest_graphics_receive.c",',
            '"guestGraphicsReceivePrimitiveImplemented": True',
            '"guestGraphicsHandleReceiveIntegrated": False',
            '"guestGraphicsImportIntegrated": False',
            '"guestGraphicsSynchronizationImplemented": False',
        ),
    )
    require(
        failures,
        "runtime contract",
        contract,
        (
            "const val guestReceivePrimitiveImplemented = true",
            "const val guestReceiveImplemented = false",
            "const val guestImportImplemented = false",
            "const val synchronizationImplemented = false",
        ),
    )

    # This primitive must not grow Vulkan import/presentation calls. Those live
    # behind separate gates and need the active Wine/DXVK device plus evidence.
    for forbidden in (
        "vkAllocateMemory",
        "vkBindImageMemory",
        "vkBindBufferMemory",
        "vkQueuePresentKHR",
        "vkCreateSwapchainKHR",
        "VkSurfaceKHR",
    ):
        if forbidden in source:
            failures.append(
                "receive primitive contains premature Vulkan/presentation operation: " + forbidden
            )

    if failures:
        print("GUEST_GRAPHICS_RECEIVE_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    print("GUEST_GRAPHICS_RECEIVE_POLICY_OK")
    print("receive_primitive_implemented=true")
    print("authenticated_runtime_receive_integrated=false")
    print("vulkan_import_implemented=false")
    print("gpu_synchronization_implemented=false")
    print("software_test_result=NOT_EXECUTED_BY_POLICY")
    print("physical_test_executed=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
