#!/usr/bin/env python3
"""Pure software tests for PocketPC PE/COFF identity validation."""

from __future__ import annotations

import struct

from pocketpc_pe import (
    PE_FILE_DLL,
    PE_MACHINE_AMD64,
    PE_OPTIONAL_MAGIC_PE32_PLUS,
    PeAuditError,
    parse_pe_identity_bytes,
)


def synthetic_pe(
    *,
    machine: int = PE_MACHINE_AMD64,
    magic: int = PE_OPTIONAL_MAGIC_PE32_PLUS,
    signature: bytes = b"PE\0\0",
    sections: int = 3,
) -> bytes:
    data = bytearray(512)
    data[:2] = b"MZ"
    pe_offset = 0x80
    struct.pack_into("<I", data, 0x3C, pe_offset)
    data[pe_offset : pe_offset + 4] = signature
    struct.pack_into(
        "<HHIIIHH",
        data,
        pe_offset + 4,
        machine,
        sections,
        0,
        0,
        0,
        0xF0,
        PE_FILE_DLL,
    )
    struct.pack_into("<H", data, pe_offset + 24, magic)
    return bytes(data)


def expect_failure(data: bytes, needle: str) -> None:
    try:
        parse_pe_identity_bytes(data)
    except PeAuditError as exc:
        if needle.lower() not in str(exc).lower():
            raise AssertionError(
                f"expected error containing {needle!r}, got {exc!r}"
            ) from exc
    else:
        raise AssertionError(f"expected PE audit failure containing {needle!r}")


def main() -> int:
    identity = parse_pe_identity_bytes(synthetic_pe())
    assert identity.format == "PE"
    assert identity.bits == 64
    assert identity.machine == "x86_64"
    assert identity.machine_id == PE_MACHINE_AMD64
    assert identity.optional_header == "PE32+"
    assert identity.optional_magic == PE_OPTIONAL_MAGIC_PE32_PLUS
    assert identity.sections == 3
    assert identity.dll is True

    expect_failure(b"MZ" + bytes(16), "too small")

    bad_mz = bytearray(synthetic_pe())
    bad_mz[:2] = b"ZZ"
    expect_failure(bytes(bad_mz), "MZ")

    expect_failure(
        synthetic_pe(signature=b"PX\0\0"),
        "PE signature",
    )
    expect_failure(
        synthetic_pe(machine=0x014C),
        "AMD64",
    )
    expect_failure(
        synthetic_pe(magic=0x010B),
        "PE32+",
    )
    expect_failure(
        synthetic_pe(sections=0),
        "zero sections",
    )

    outside = bytearray(synthetic_pe())
    struct.pack_into("<I", outside, 0x3C, 0x10000)
    expect_failure(bytes(outside), "outside")

    truncated_optional = bytearray(synthetic_pe())
    struct.pack_into("<H", truncated_optional, 0x80 + 20, 0x400)
    expect_failure(bytes(truncated_optional), "beyond")

    print("POCKETPC_PE_AUDIT_TEST_OK")
    print("pe_amd64=guarded")
    print("pe32_plus=guarded")
    print("mz_only_false_positive=rejected")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
