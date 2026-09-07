#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"
LEADING_LOGICAL = re.compile(r"^\s*-(and|or)\b", re.IGNORECASE)
READONLY_AUTOMATIC_VARIABLES = (
    "HOME",
    "Host",
    "PID",
    "PSCommandPath",
    "PSHOME",
    "PSScriptRoot",
    "PSVersionTable",
    "PWD",
    "ShellId",
)
READONLY_NAME = "(?:" + "|".join(READONLY_AUTOMATIC_VARIABLES) + ")"
READONLY_ASSIGNMENT = re.compile(
    rf"(?im)^\s*\${READONLY_NAME}\s*=",
)
READONLY_TYPED_DECLARATION = re.compile(
    rf"(?i)\[[^\]\r\n]+\]\s*\${READONLY_NAME}\b",
)
READONLY_LOOP_DECLARATION = re.compile(
    rf"(?i)\bforeach\s*\(\s*\${READONLY_NAME}\s+in\b",
)
SAFE_NATIVE_CAPTURE = re.compile(
    r"^\s*\$(?:output|result)\s*=\s*&\s*\$FilePath\s+@Arguments\s+2>&1\s*$",
    re.IGNORECASE,
)
UTF8_BOM = b"\xef\xbb\xbf"


def main() -> int:
    failures: list[str] = []
    ps1_files = sorted(SCRIPTS.glob("*.ps1"))

    if not ps1_files:
        failures.append("no PowerShell scripts found")

    for path in ps1_files:
        raw = path.read_bytes()
        text = raw.decode("utf-8")
        relative = path.relative_to(ROOT)

        if not raw.startswith(UTF8_BOM):
            non_ascii_offset = next(
                (offset for offset, byte in enumerate(raw) if byte >= 0x80),
                None,
            )
            if non_ascii_offset is not None:
                number = raw[:non_ascii_offset].count(b"\n") + 1
                failures.append(
                    f"{relative}:{number}: non-ASCII PowerShell source must use "
                    "a UTF-8 BOM so Windows PowerShell 5.1 decodes it correctly"
                )

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

        if (
            READONLY_ASSIGNMENT.search(text)
            or READONLY_TYPED_DECLARATION.search(text)
            or READONLY_LOOP_DECLARATION.search(text)
        ):
            failures.append(
                f"{relative}: do not declare or assign a readonly PowerShell "
                "automatic variable"
            )

        lines = text.splitlines()
        for index, line in enumerate(lines):
            number = index + 1
            if LEADING_LOGICAL.search(line):
                failures.append(
                    f"{relative}:{number}: "
                    "logical operator starts a continuation line"
                )

            if "2>&1" in line:
                before = "\n".join(lines[max(0, index - 8) : index])
                after = "\n".join(lines[index + 1 : index + 10])
                if not SAFE_NATIVE_CAPTURE.fullmatch(line):
                    failures.append(
                        f"{relative}:{number}: native stderr capture must use "
                        "the guarded Invoke-NativeCapture pattern"
                    )
                elif (
                    '$ErrorActionPreference = "Continue"' not in before
                    or "$LASTEXITCODE" not in after
                    or "$ErrorActionPreference = $previousErrorActionPreference" not in after
                ):
                    failures.append(
                        f"{relative}:{number}: native stderr capture is missing "
                        "ErrorActionPreference/exit-code guards"
                    )

        if path.name == "first-physical-test-core-windows.ps1":
            marker = "# POCKETPC_FIRST_PHYSICAL_TEST_CORE_EOF"
            if text.count(marker) != 1:
                failures.append("first physical core must contain exactly one EOF marker")
            elif text.strip().splitlines()[-1].strip() != marker:
                failures.append("first physical core contains content after EOF marker")

            try:
                text.encode("ascii")
            except UnicodeEncodeError:
                failures.append(
                    "first physical core must remain ASCII-only for Windows PowerShell 5.1"
                )

            commit_regex = "$commit -notmatch '^[0-9a-fA-F]{40}$'"
            if text.count(commit_regex) != 1:
                failures.append(
                    "first physical core Git commit regex sentinel is missing, "
                    "duplicated, or truncated"
                )

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
                        f"scripts/first-physical-test-core-windows.ps1:{number}: "
                        "unclosed quoted string"
                    )

            if (paren, brace, bracket) != (0, 0, 0):
                failures.append(
                    "first physical core delimiter balance is not zero: "
                    f"paren={paren} brace={brace} bracket={bracket}"
                )

        if path.name == "build-local-windows.ps1":
            if "$policyOutput = Invoke-NativeCapture $python (" not in text:
                failures.append(
                    "Windows builder must capture each Python policy output "
                    "before writing it to the host"
                )
            if "$pythonPolicyState -isnot [string]" not in text:
                failures.append(
                    "Windows builder must reject non-string Python policy states"
                )
            if re.search(r"(?m)^\s*Invoke-Native\s+\$python\b", text):
                failures.append(
                    "Windows builder must not leak Python policy stdout into "
                    "the returned policy state"
                )

        if path.name == "install-device-windows.ps1":
            required_install_sentinels = (
                "[int]$InstallTimeoutSeconds = 180",
                "function Invoke-AdbInstallWithTimeout",
                "Start-Process",
                "$process.WaitForExit($TimeoutSeconds * 1000)",
                '[Device Install] {0}',
                'Instalando APK via ADB (timeout: {0}s)',
            )
            for sentinel in required_install_sentinels:
                if sentinel not in text:
                    failures.append(
                        "Windows device install gate is missing bounded/progress "
                        f"sentinel: {sentinel}"
                    )
            if 'Invoke-NativeCapture $adb @("-s", $serial, "install"' in text:
                failures.append(
                    "Windows device install gate must not run adb install "
                    "through the unbounded native capture path"
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
