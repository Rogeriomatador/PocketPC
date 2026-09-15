#!/usr/bin/env python3
"""Fail-closed policy lock for Wine PocketPC PE artifact identity evidence."""

from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
PE_PARSER = ROOT / "scripts/pocketpc_pe.py"
PE_AUDITOR = ROOT / "scripts/audit-wine-pocketpc-driver-pe.py"
PE_TEST = ROOT / "scripts/test-pocketpc-pe-audit.py"
WORKFLOW = ROOT / ".github/workflows/wine-pocketpc-driver-build.yml"


def require(
    failures: list[str],
    label: str,
    text: str,
    sentinels: tuple[str, ...],
) -> None:
    for sentinel in sentinels:
        if sentinel not in text:
            failures.append(f"{label} missing: {sentinel}")


def read_required(failures: list[str], path: Path) -> str:
    if not path.is_file():
        failures.append(f"missing required file: {path.relative_to(ROOT)}")
        return ""
    return path.read_text(encoding="utf-8")


def main() -> int:
    failures: list[str] = []

    parser = read_required(failures, PE_PARSER)
    auditor = read_required(failures, PE_AUDITOR)
    test = read_required(failures, PE_TEST)
    workflow = read_required(failures, WORKFLOW)

    require(
        failures,
        "PE parser",
        parser,
        (
            "PE_MACHINE_AMD64 = 0x8664",
            "PE_OPTIONAL_MAGIC_PE32_PLUS = 0x020B",
            'data[:2] != b"MZ"',
            'b"PE\\0\\0"',
            "machine != PE_MACHINE_AMD64",
            "optional_magic != PE_OPTIONAL_MAGIC_PE32_PLUS",
            "sections == 0",
        ),
    )
    require(
        failures,
        "PE auditor",
        auditor,
        (
            "parse_pe_identity(args.driver)",
            '"scope": "static_binary_identity"',
            '"amd64_machine": True',
            '"pe32_plus": True',
            '"compiled_or_loadable_in_wine": False',
            '"physical_android_validation": False',
            "WINE_POCKETPC_PE_AUDIT_OK",
            "WINE_POCKETPC_PE_AUDIT_FAIL=",
        ),
    )
    require(
        failures,
        "PE synthetic test",
        test,
        (
            "synthetic_pe(",
            "machine=0x014C",
            "magic=0x010B",
            "sections=0",
            'print("POCKETPC_PE_AUDIT_TEST_OK")',
            'print("mz_only_false_positive=rejected")',
        ),
    )
    require(
        failures,
        "Wine driver workflow",
        workflow,
        (
            "scripts/pocketpc_pe.py",
            "scripts/audit-wine-pocketpc-driver-pe.py",
            "scripts/test-pocketpc-pe-audit.py",
            "scripts/test-wine-pocketpc-pe-policy.py",
            "python3 scripts/test-pocketpc-pe-audit.py",
            "python3 scripts/test-wine-pocketpc-pe-policy.py",
            "python3 scripts/audit-wine-pocketpc-driver-pe.py",
            "winepocketpc-driver-pe-audit.json",
        ),
    )

    build_pos = workflow.find("python3 scripts/build-wine-pocketpc-driver.py")
    audit_pos = workflow.find("python3 scripts/audit-wine-pocketpc-driver-pe.py")
    upload_pos = workflow.find("Upload Wine PocketPC driver evidence")
    if build_pos < 0 or audit_pos < 0 or audit_pos <= build_pos:
        failures.append("PE artifact audit must run after the isolated driver build")
    if upload_pos < 0 or audit_pos >= upload_pos:
        failures.append("PE artifact audit must run before evidence upload")

    if failures:
        print("WINE_POCKETPC_PE_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    print("WINE_POCKETPC_PE_POLICY_OK")
    print("pe_machine=AMD64")
    print("pe_optional_header=PE32+")
    print("pe_audit_before_artifact_upload=true")
    print("wine_driver_load_evidence=false")
    print("physical_android_validation=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
