#!/usr/bin/env python3
"""PocketPC Windows PowerShell 5.1 compatibility smoke checks.

This is intentionally narrow: it protects parser patterns that have already
failed on the project's real Windows PowerShell 5.1 execution path.
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"

LEADING_LOGICAL = re.compile(r"^\s*-(and|or)\b", re.IGNORECASE)


def main() -> int:
    failures: list[str] = []
    ps1_files = sorted(SCRIPTS.glob("*.ps1"))

    if not ps1_files:
        failures.append("no PowerShell scripts found")

    for path in ps1_files:
        text = path.read_text(encoding="utf-8")
        for number, line in enumerate(
            text.splitlines(),
            start=1,
        ):
            if LEADING_LOGICAL.search(line):
                failures.append(
                    f"{path.relative_to(ROOT)}:{number}: "
                    "logical operator starts a continuation line; "
                    "Windows PowerShell 5.1 parser rejected this pattern"
                )

        if path.name == "doctor-windows.ps1":
            marker = "# POCKETPC_DOCTOR_EOF"
            if text.count(marker) != 1:
                failures.append(
                    "scripts/doctor-windows.ps1 must contain exactly one EOF marker"
                )
            elif text.strip().splitlines()[-1].strip() != marker:
                failures.append(
                    "scripts/doctor-windows.ps1 contains content after EOF marker"
                )

    if failures:
        print("POWERSHELL51_COMPAT_FAILED", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("POWERSHELL51_COMPAT_OK")
    print(f"scripts_checked={len(ps1_files)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
