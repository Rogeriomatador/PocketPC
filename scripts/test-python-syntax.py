#!/usr/bin/env python3
from __future__ import annotations

import ast
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"


def main() -> int:
    failures: list[str] = []
    checked = 0

    for path in sorted(
        SCRIPTS.rglob("*.py")
    ):
        if not path.is_file():
            continue

        checked += 1
        relative = path.relative_to(ROOT)

        try:
            source = path.read_text(
                encoding="utf-8-sig",
            )
        except Exception as error:
            failures.append(
                f"{relative}:read:{error}"
            )
            continue

        try:
            ast.parse(
                source,
                filename=str(relative),
            )
        except SyntaxError as error:
            location = (
                f"{error.lineno or 0}:"
                f"{error.offset or 0}"
            )
            failures.append(
                f"{relative}:{location}:"
                f"{error.msg}"
            )

    if checked == 0:
        print(
            "PYTHON_SYNTAX_POLICY_FAILED",
            file=sys.stderr,
        )
        print(
            "- no Python scripts discovered",
            file=sys.stderr,
        )
        return 1

    if failures:
        print(
            "PYTHON_SYNTAX_POLICY_FAILED",
            file=sys.stderr,
        )
        for failure in failures:
            print(
                "- " + failure,
                file=sys.stderr,
            )
        return 1

    print("PYTHON_SYNTAX_POLICY_OK")
    print(f"scripts_checked={checked}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
