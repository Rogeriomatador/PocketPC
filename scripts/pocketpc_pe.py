#!/usr/bin/env python3
"""Minimal fail-closed PE/COFF identity parser for PocketPC build evidence."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from pathlib import Path
import struct

PE_MACHINE_AMD64 = 0x8664
PE_OPTIONAL_MAGIC_PE32_PLUS = 0x020B
PE_FILE_DLL = 0x2000


class PeAuditError(RuntimeError):
    pass


@dataclass(frozen=True)
class PeIdentity:
    format: str
    bits: int
    machine: str
    machine_id: int
    optional_header: str
    optional_magic: int
    sections: int
    characteristics: int
    dll: bool
    pe_offset: int

    def to_json(self) -> dict[str, object]:
        return asdict(self)


def parse_pe_identity_bytes(data: bytes) -> PeIdentity:
    if len(data) < 0x40:
        raise PeAuditError("PE file is too small to contain a DOS header")
    if data[:2] != b"MZ":
        raise PeAuditError("PE file is missing the MZ DOS signature")

    pe_offset = struct.unpack_from("<I", data, 0x3C)[0]
    if pe_offset < 0x40:
        raise PeAuditError(f"invalid PE header offset: 0x{pe_offset:x}")
    if pe_offset > len(data) - 24:
        raise PeAuditError(
            f"PE header offset 0x{pe_offset:x} falls outside file size {len(data)}"
        )
    if data[pe_offset : pe_offset + 4] != b"PE\0\0":
        raise PeAuditError("PE signature is missing at e_lfanew")

    (
        machine,
        sections,
        _timestamp,
        _pointer_to_symbols,
        _symbol_count,
        optional_size,
        characteristics,
    ) = struct.unpack_from("<HHIIIHH", data, pe_offset + 4)

    optional_offset = pe_offset + 24
    if optional_size < 2:
        raise PeAuditError("PE optional header is missing")
    optional_end = optional_offset + optional_size
    if optional_end > len(data):
        raise PeAuditError(
            "PE optional header extends beyond the end of the file"
        )

    optional_magic = struct.unpack_from("<H", data, optional_offset)[0]

    if machine != PE_MACHINE_AMD64:
        raise PeAuditError(
            f"unexpected PE machine 0x{machine:04x}; expected AMD64 0x8664"
        )
    if optional_magic != PE_OPTIONAL_MAGIC_PE32_PLUS:
        raise PeAuditError(
            "unexpected PE optional-header magic "
            f"0x{optional_magic:04x}; expected PE32+ 0x020b"
        )
    if sections == 0:
        raise PeAuditError("PE image contains zero sections")

    return PeIdentity(
        format="PE",
        bits=64,
        machine="x86_64",
        machine_id=machine,
        optional_header="PE32+",
        optional_magic=optional_magic,
        sections=sections,
        characteristics=characteristics,
        dll=bool(characteristics & PE_FILE_DLL),
        pe_offset=pe_offset,
    )


def parse_pe_identity(path: Path | str) -> PeIdentity:
    file_path = Path(path)
    if not file_path.is_file():
        raise PeAuditError(f"PE file does not exist: {file_path}")
    return parse_pe_identity_bytes(file_path.read_bytes())
