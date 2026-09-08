#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"


def main() -> int:
    failures: list[str] = []
    checked = 0

    for path in sorted(SCRIPTS.glob("*.py")):
        checked += 1
        try:
            source = path.read_text(encoding="utf-8-sig")
            compile(source, str(path), "exec")
        except Exception as exc:
            failures.append(
                f"{path.relative_to(ROOT)}: "
                f"{exc.__class__.__name__}: {exc}"
            )

    if failures:
        print("PYTHON_SCRIPT_SYNTAX_FAILED", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        print(f"scripts_checked={checked}", file=sys.stderr)
        return 1

    print("PYTHON_SCRIPT_SYNTAX_OK")
    print(f"scripts_checked={checked}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
