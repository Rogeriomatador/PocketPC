#!/usr/bin/env python3
"""Audit the PE half of the isolated PocketPC Wine display driver build."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from pocketpc_pe import PeAuditError, parse_pe_identity


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--driver", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()

    try:
        identity = parse_pe_identity(args.driver)
    except PeAuditError as exc:
        print(f"WINE_POCKETPC_PE_AUDIT_FAIL={exc}")
        return 1

    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "schema": 1,
        "status": "pass",
        "scope": "static_binary_identity",
        "driver": str(args.driver.resolve()),
        "sha256": sha256(args.driver),
        "pe": identity.to_json(),
        "claims": {
            "dos_signature": True,
            "pe_signature": True,
            "amd64_machine": True,
            "pe32_plus": True,
            "compiled_or_loadable_in_wine": False,
            "physical_android_validation": False,
        },
    }
    args.evidence.write_text(
        json.dumps(payload, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )

    print("WINE_POCKETPC_PE_AUDIT_OK")
    print("pe_machine=x86_64")
    print("pe_optional_header=PE32+")
    print(f"pe_sections={identity.sections}")
    print(f"pe_dll={str(identity.dll).lower()}")
    print(f"pe_sha256={payload['sha256']}")
    print(f"pe_evidence={args.evidence}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
