#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
HEADER = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_graphics_transport.h"
SOURCE = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_graphics_transport.c"
KOTLIN_DESCRIPTOR = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GuestGraphicsResourceDescriptor.kt"
KOTLIN_OWNERSHIP = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GuestGraphicsOwnershipProtocol.kt"
CONTRACT = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GuestGraphicsTransportContract.kt"
MAKEFILE = ROOT / "third_party/wine/pocketpc-driver/Makefile.in"
PREPARER = ROOT / "scripts/prepare-wine-pocketpc-driver.py"


def require(failures: list[str], label: str, text: str, markers: tuple[str, ...]) -> None:
    for marker in markers:
        if marker not in text:
            failures.append(f"{label} missing: {marker}")


def define(text: str, name: str) -> int | None:
    match = re.search(rf"^#define\s+{re.escape(name)}\s+([0-9]+)u?$", text, re.MULTILINE)
    return int(match.group(1)) if match else None


def main() -> int:
    failures: list[str] = []
    header = HEADER.read_text(encoding="utf-8")
    source = SOURCE.read_text(encoding="utf-8")
    descriptor = KOTLIN_DESCRIPTOR.read_text(encoding="utf-8")
    ownership = KOTLIN_OWNERSHIP.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")
    makefile = MAKEFILE.read_text(encoding="utf-8")
    preparer = PREPARER.read_text(encoding="utf-8")

    if define(header, "PGT_VERSION") != 1:
        failures.append("C graphics transport protocol version is not 1")
    if define(header, "PGT_HEADER_BYTES") != 24:
        failures.append("C graphics header size changed")
    if define(header, "PGT_RESOURCE_DESCRIPTOR_BYTES") != 64:
        failures.append("C resource descriptor size changed")
    if define(header, "PGT_MAX_DIMENSION") != 16384:
        failures.append("C max dimension changed")
    if define(header, "PGT_MAX_LAYERS") != 256:
        failures.append("C max layers changed")

    require(
        failures,
        "C graphics transport header",
        header,
        (
            "PGT_MSG_RESOURCE_OFFER",
            "PGT_MSG_GUEST_IMPORTED",
            "PGT_MSG_GUEST_RENDER_BEGIN",
            "PGT_MSG_GUEST_RENDER_COMPLETE",
            "PGT_MSG_HOST_PRESENT_BEGIN",
            "PGT_MSG_HOST_PRESENT_COMPLETE",
            "PGT_MSG_RESOURCE_RETIRE",
            "struct pgt_resource_descriptor",
            "struct pgt_ownership_record",
        ),
    )
    require(
        failures,
        "C graphics ownership implementation",
        source,
        (
            "pgt_validate_resource_descriptor",
            "pgt_next_ownership_state",
            "pgt_validate_ownership_transition",
            "current->sequence == UINT64_MAX",
            "next_sequence != current->sequence + 1u",
        ),
    )
    require(
        failures,
        "Kotlin descriptor protocol",
        descriptor,
        (
            "const val CURRENT_PROTOCOL = 1",
            "const val MAX_DIMENSION = 16_384",
            "const val MAX_LAYERS = 256",
            "fields.keys != REQUIRED_FIELDS",
        ),
    )
    require(
        failures,
        "Kotlin ownership protocol",
        ownership,
        (
            "const val PROTOCOL_VERSION = 1",
            "sequence != token.sequence + 1L",
            "GuestGraphicsOwnershipState.GUEST_RENDERING",
            "GuestGraphicsOwnershipState.HOST_PRESENTING",
            "BLOCKER_INVALID_TRANSITION",
        ),
    )
    require(
        failures,
        "guest transport fail-closed contract",
        contract,
        (
            "const val descriptorProtocolImplemented = true",
            "const val ownershipProtocolImplemented = true",
            "const val guestReceiveImplemented = false",
            "const val guestImportImplemented = false",
            "const val synchronizationImplemented = false",
        ),
    )
    require(
        failures,
        "Wine driver build",
        makefile,
        (
            "\tpocketpc_graphics_transport.c \\",
        ),
    )
    require(
        failures,
        "Wine overlay",
        preparer,
        (
            '"pocketpc_graphics_transport.h",',
            '"pocketpc_graphics_transport.c",',
            '"guestGraphicsDescriptorProtocolImplemented": True',
            '"guestGraphicsOwnershipProtocolImplemented": True',
            '"guestGraphicsHandleReceiveIntegrated": False',
            '"guestGraphicsImportIntegrated": False',
            '"guestGraphicsSynchronizationImplemented": False',
        ),
    )

    # Resource identity must not be a raw process-local object. The C wire
    # descriptor contains metadata only; no fd/native-window/pointer member is
    # allowed to creep in before an out-of-band handle transport is designed.
    descriptor_struct = header.split("struct pgt_resource_descriptor", 1)[1].split("};", 1)[0]
    for forbidden in (" fd;", "fd_", "pointer", "native_window", "anativewindow"):
        if forbidden.lower() in descriptor_struct.lower():
            failures.append("C resource descriptor contains process-local field: " + forbidden)

    if failures:
        print("GUEST_GRAPHICS_PROTOCOL_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    print("GUEST_GRAPHICS_PROTOCOL_POLICY_OK")
    print("protocol_version=1")
    print("descriptor_bytes=64")
    print("ownership_protocol_implemented=true")
    print("guest_handle_receive_implemented=false")
    print("guest_vulkan_import_implemented=false")
    print("gpu_synchronization_implemented=false")
    print("execution_evidence=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
