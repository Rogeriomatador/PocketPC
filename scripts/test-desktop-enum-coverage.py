#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]

MODELS = ROOT / "app/src/main/java/dev/pocketpc/core/desktop/DesktopModels.kt"
COMMANDS = ROOT / "app/src/main/java/dev/pocketpc/core/desktop/DesktopCommand.kt"
SHELL = ROOT / "app/src/main/java/dev/pocketpc/core/ui/PocketPcApp.kt"
CHROME = ROOT / "app/src/main/java/dev/pocketpc/core/ui/DesktopChrome.kt"

APP_ENUM = re.compile(r"^\s{4}([A-Z][A-Z0-9_]*)\(", re.MULTILINE)
APP_CASE = re.compile(r"DesktopApp\.([A-Z][A-Z0-9_]*)\s*->")
COMMAND_ENUM = re.compile(r"^\s{4}([A-Z][A-Z0-9_]*),\s*$", re.MULTILINE)
COMMAND_CASE = re.compile(r"DesktopCommand\.([A-Z][A-Z0-9_]*)\s*->")
DESKTOP_REF = re.compile(r"DesktopApp\.([A-Z][A-Z0-9_]*)")


def read(path: pathlib.Path) -> str:
    if not path.is_file():
        raise SystemExit(f"missing source file: {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8-sig")


def unique(values: list[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        if value not in seen:
            seen.add(value)
            result.append(value)
    return result


def extract_function_body(text: str, function_name: str) -> str:
    marker = f"fun {function_name}"
    start = text.find(marker)
    if start < 0:
        return ""

    brace = text.find("{", start)
    if brace < 0:
        # Expression-bodied functions such as "= listOf(...)".
        end = text.find("\n\n", start)
        return text[start:] if end < 0 else text[start:end]

    depth = 0
    for index in range(brace, len(text)):
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start : index + 1]
    return text[start:]


def main() -> int:
    failures: list[str] = []

    models = read(MODELS)
    commands = read(COMMANDS)
    shell = read(SHELL)
    chrome = read(CHROME)

    apps = unique(APP_ENUM.findall(models))
    shell_apps = unique(APP_CASE.findall(shell))
    icon_apps = unique(APP_CASE.findall(chrome))

    if not apps:
        failures.append("DesktopApp enum could not be parsed")

    missing_shell = sorted(set(apps) - set(shell_apps))
    extra_shell = sorted(set(shell_apps) - set(apps))
    missing_icons = sorted(set(apps) - set(icon_apps))
    extra_icons = sorted(set(icon_apps) - set(apps))

    if missing_shell:
        failures.append(
            "DesktopApp values missing PocketPcApp window handler: "
            + ", ".join(missing_shell)
        )
    if extra_shell:
        failures.append(
            "PocketPcApp references unknown DesktopApp values: "
            + ", ".join(extra_shell)
        )
    if missing_icons:
        failures.append(
            "DesktopApp values missing AppIconTile Canvas case: "
            + ", ".join(missing_icons)
        )
    if extra_icons:
        failures.append(
            "DesktopChrome references unknown DesktopApp values: "
            + ", ".join(extra_icons)
        )

    command_values = unique(COMMAND_ENUM.findall(commands))
    command_handlers = unique(COMMAND_CASE.findall(shell))
    missing_commands = sorted(set(command_values) - set(command_handlers))
    extra_commands = sorted(set(command_handlers) - set(command_values))

    if not command_values:
        failures.append("DesktopCommand enum could not be parsed")
    if missing_commands:
        failures.append(
            "DesktopCommand values missing PocketPcApp handler: "
            + ", ".join(missing_commands)
        )
    if extra_commands:
        failures.append(
            "PocketPcApp references unknown DesktopCommand values: "
            + ", ".join(extra_commands)
        )

    shortcut_body = extract_function_body(models, "defaultDesktopShortcuts")
    shortcut_refs = unique(DESKTOP_REF.findall(shortcut_body))
    unknown_shortcuts = sorted(set(shortcut_refs) - set(apps))
    if not shortcut_refs:
        failures.append("defaultDesktopShortcuts is empty or could not be parsed")
    if unknown_shortcuts:
        failures.append(
            "defaultDesktopShortcuts references unknown apps: "
            + ", ".join(unknown_shortcuts)
        )

    if failures:
        print("DESKTOP_ENUM_COVERAGE_FAILED", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("DESKTOP_ENUM_COVERAGE_OK")
    print(f"apps={len(apps)}")
    print(f"commands={len(command_values)}")
    print(f"default_shortcuts={len(shortcut_refs)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
