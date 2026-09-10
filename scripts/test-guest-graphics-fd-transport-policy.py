#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / "third_party/wine/pocketpc-display-bridge"
HEADER = BRIDGE / "pocketpc_fd_transport.h"
SOURCE = BRIDGE / "pocketpc_fd_transport.c"
SMOKE = BRIDGE / "fd_transport_smoke.c"
BINDING_HEADER = BRIDGE / "pocketpc_graphics_handle_binding.h"
BINDING_SOURCE = BRIDGE / "pocketpc_graphics_handle_binding.c"
BINDING_SMOKE = BRIDGE / "graphics_handle_binding_smoke.c"
MAKEFILE = ROOT / "third_party/wine/pocketpc-driver/Makefile.in"
PREPARER = ROOT / "scripts/prepare-wine-pocketpc-driver.py"
RUNNER = ROOT / "scripts/run-pocketpc-fd-transport-smoke.py"
CONTRACT = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GuestGraphicsTransportContract.kt"
WORKFLOW = ROOT / ".github/workflows/wine-pocketpc-driver-build.yml"


def require(
    failures: list[str],
    label: str,
    text: str,
    markers: tuple[str, ...],
) -> None:
    for marker in markers:
        if marker not in text:
            failures.append(f"{label} missing: {marker}")


def main() -> int:
    failures: list[str] = []
    paths = (
        HEADER,
        SOURCE,
        SMOKE,
        BINDING_HEADER,
        BINDING_SOURCE,
        BINDING_SMOKE,
        MAKEFILE,
        PREPARER,
        RUNNER,
        CONTRACT,
        WORKFLOW,
    )
    for path in paths:
        if not path.is_file():
            failures.append(f"missing: {path.relative_to(ROOT)}")

    if failures:
        print("GUEST_GRAPHICS_FD_TRANSPORT_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    header = HEADER.read_text(encoding="utf-8")
    source = SOURCE.read_text(encoding="utf-8")
    smoke = SMOKE.read_text(encoding="utf-8")
    binding_header = BINDING_HEADER.read_text(encoding="utf-8")
    binding_source = BINDING_SOURCE.read_text(encoding="utf-8")
    binding_smoke = BINDING_SMOKE.read_text(encoding="utf-8")
    makefile = MAKEFILE.read_text(encoding="utf-8")
    preparer = PREPARER.read_text(encoding="utf-8")
    runner = RUNNER.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")
    workflow = WORKFLOW.read_text(encoding="utf-8")

    require(
        failures,
        "fd transport header",
        header,
        (
            "POCKETPC_FD_TRANSPORT_VERSION 1u",
            "POCKETPC_FD_TRANSPORT_TOKEN_BYTES 32u",
            "struct pocketpc_fd_transport_token",
            "resource_id",
            "generation",
            "sequence",
            "POCKETPC_FD_TRANSPORT_WRONG_SOCKET_TYPE",
            "SCM_RIGHTS",
            "SOCK_SEQPACKET",
            "FD_CLOEXEC",
        ),
    )
    require(
        failures,
        "fd transport implementation",
        source,
        (
            "sendmsg(",
            "recvmsg(",
            "SCM_RIGHTS",
            "CMSG_SPACE(sizeof(int))",
            "SO_TYPE",
            "SOCK_SEQPACKET",
            "MSG_CTRUNC",
            "MSG_TRUNC",
            "pfd_close_received_rights(&message);",
            "pfd_set_cloexec(candidate_fd)",
            "FD_CLOEXEC",
            "pfd_decode_token(payload, token)",
        ),
    )
    if source.count("pfd_close_received_rights(&message);") < 2:
        failures.append(
            "rejected ancillary descriptors are not closed on guarded receive paths"
        )

    for forbidden in (
        "raw_pointer",
        "native_window",
        "AHardwareBuffer *",
        "VkSurfaceKHR *",
    ):
        if forbidden in header:
            failures.append(
                "fd token header contains forbidden process-local identity: " + forbidden
            )

    require(
        failures,
        "fd transport smoke",
        smoke,
        (
            "socketpair(AF_UNIX, SOCK_SEQPACKET",
            "socketpair(AF_UNIX, SOCK_STREAM",
            "POCKETPC_FD_TRANSPORT_WRONG_SOCKET_TYPE",
            "zero-sequence-accepted",
            "descriptor-content-mismatch",
            "cloexec-query-failed",
            'puts("seqpacket_required=true")',
            'puts("vulkan_resource_import_executed=false")',
        ),
    )

    require(
        failures,
        "graphics handle binding header",
        binding_header,
        (
            "pocketpc_graphics_handle_binding_validate_offer",
            "POCKETPC_GRAPHICS_HANDLE_BINDING_RESOURCE_MISMATCH",
            "POCKETPC_GRAPHICS_HANDLE_BINDING_GENERATION_MISMATCH",
            "POCKETPC_GRAPHICS_HANDLE_BINDING_SEQUENCE_MISMATCH",
            "POCKETPC_GRAPHICS_HANDLE_BINDING_WRONG_STATE",
            "does not import memory into Vulkan",
        ),
    )
    require(
        failures,
        "graphics handle binding implementation",
        binding_source,
        (
            "pgt_validate_resource_descriptor(descriptor)",
            "ownership->state != PGT_STATE_OFFERED_TO_GUEST",
            "descriptor->resource_id != token->resource_id",
            "descriptor->generation != token->generation",
            "descriptor->sync_sequence != token->sequence",
        ),
    )
    require(
        failures,
        "graphics handle binding smoke",
        binding_smoke,
        (
            "valid-offer-rejected",
            "resource-mismatch-not-rejected",
            "generation-mismatch-not-rejected",
            "sequence-mismatch-not-rejected",
            "wrong-state-not-rejected",
            'puts("GRAPHICS_HANDLE_BINDING_SMOKE_OK")',
            'puts("vulkan_import_executed=false")',
        ),
    )

    require(
        failures,
        "graphics foundation runner",
        runner,
        (
            '"-Wall"',
            '"-Wextra"',
            '"-Werror"',
            '"-Wpedantic"',
            '"GRAPHICS_FD_FOUNDATION_SOFTWARE_TEST_PASS"',
            '"classification": "SOFTWARE_TEST"',
            '"resourceIdentityGuard": True',
            '"generationGuard": True',
            '"sequenceGuard": True',
            '"guestVulkanResourceImportExecuted": False',
            '"guestGpuSynchronizationExecuted": False',
            '"androidPhysicalTestExecuted": False',
            '"robloxExecuted": False',
        ),
    )

    require(
        failures,
        "Wine unixlib integration",
        makefile,
        (
            "\tpocketpc_fd_transport.c \\",
            "\tpocketpc_graphics_handle_binding.c \\",
        ),
    )
    require(
        failures,
        "Wine overlay evidence",
        preparer,
        (
            '"schemaVersion": 4',
            '"pocketpc_fd_transport.h",',
            '"pocketpc_fd_transport.c",',
            '"pocketpc_graphics_handle_binding.h",',
            '"pocketpc_graphics_handle_binding.c",',
            '"graphicsFdTransportProtocolVersion": 1',
            '"guestGraphicsAncillaryFdTransportPrimitiveImplemented": True',
            '"guestGraphicsHandleBindingImplemented": True',
            '"guestGraphicsHandleReceiveImplemented": False',
            '"guestGraphicsImportImplemented": False',
            '"guestGraphicsSynchronizationImplemented": False',
        ),
    )
    require(
        failures,
        "runtime contract distinction",
        contract,
        (
            "const val ancillaryFdTransportPrimitiveImplemented =\n        true",
            "const val guestReceiveImplemented =\n        false",
            "const val guestImportImplemented =\n        false",
            "const val synchronizationImplemented =\n        false",
        ),
    )
    require(
        failures,
        "CI execution hook",
        workflow,
        (
            "scripts/test-guest-graphics-fd-transport-policy.py",
            "scripts/run-pocketpc-fd-transport-smoke.py",
            "Compile and execute ancillary FD transport smoke",
            "fd-transport-evidence.json",
        ),
    )

    if failures:
        print("GUEST_GRAPHICS_FD_TRANSPORT_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    print("GUEST_GRAPHICS_FD_TRANSPORT_POLICY_OK")
    print("ancillary_fd_transport_primitive_implemented=true")
    print("graphics_handle_binding_implemented=true")
    print("seqpacket_required=true")
    print("rejected_rights_cleanup_guarded=true")
    print("guest_receive_implemented=false")
    print("guest_vulkan_import_implemented=false")
    print("guest_gpu_synchronization_implemented=false")
    print("software_test_result=NOT_EXECUTED_BY_POLICY")
    print("physical_test_executed=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
