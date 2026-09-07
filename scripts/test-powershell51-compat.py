#!/usr/bin/env python3
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

        if "New-Object System.Collections.Generic.List[object]" in text:
            failures.append(
                f"{path.relative_to(ROOT)}: avoid New-Object List[object]; "
                "PowerShell binder can wrap it incompatibly"
            )
        if "@($checks)" in text:
            failures.append(
                f"{path.relative_to(ROOT)}: avoid @($checks) over generic List[object]; "
                "use ToArray()"
            )

        if "& $java -version 2>&1" in text:
            failures.append(
                f"{path.relative_to(ROOT)}: do not capture java -version with 2>&1 under Stop; "
                "use System.Diagnostics.Process"
            )

        if re.search(r"\[string\]\$Home\b", text, re.IGNORECASE):
            failures.append(
                f"{path.relative_to(ROOT)}: do not use $Home as a parameter; "
                "PowerShell $HOME is a readonly automatic variable"
            )
        for number, line in enumerate(text.splitlines(), start=1):
            if LEADING_LOGICAL.search(line):
                failures.append(
                    f"{path.relative_to(ROOT)}:{number}: "
                    "logical operator starts a continuation line"
                )

        if path.name == "doctor-windows.ps1":
            marker = "# POCKETPC_DOCTOR_EOF"
            if text.count(marker) != 1:
                failures.append("doctor must contain exactly one EOF marker")
            elif text.strip().splitlines()[-1].strip() != marker:
                failures.append("doctor contains content after EOF marker")

            git_head_regex = "($head.Text -match '^[0-9a-fA-F]{40}$')"
            if git_head_regex not in text:
                failures.append("doctor Git HEAD regex sentinel missing/truncated")

            paren = brace = bracket = 0
            for number, line in enumerate(text.splitlines(), start=1):
                in_single = False
                in_double = False
                index = 0
                while index < len(line):
                    char = line[index]
                    if not in_single and not in_double and char == "#":
                        break
                    if char == "'" and not in_double:
                        if in_single and index + 1 < len(line) and line[index + 1] == "'":
                            index += 2
                            continue
                        in_single = not in_single
                        index += 1
                        continue
                    if char == '"' and not in_single:
                        if index > 0 and line[index - 1] == "`":
                            index += 1
                            continue
                        in_double = not in_double
                        index += 1
                        continue
                    if not in_single and not in_double:
                        if char == "(":
                            paren += 1
                        elif char == ")":
                            paren -= 1
                        elif char == "{":
                            brace += 1
                        elif char == "}":
                            brace -= 1
                        elif char == "[":
                            bracket += 1
                        elif char == "]":
                            bracket -= 1
                    index += 1

                if in_single or in_double:
                    failures.append(
                        f"scripts/doctor-windows.ps1:{number}: unclosed quoted string"
                    )

            if (paren, brace, bracket) != (0, 0, 0):
                failures.append(
                    "doctor delimiter balance is not zero: "
                    f"paren={paren} brace={brace} bracket={bracket}"
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
