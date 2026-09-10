#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE_ROOT = ROOT / "app/src/main/java"

BROWSER = pathlib.Path("dev/pocketpc/core/ui/BrowserApp.kt")
STORAGE = pathlib.Path("dev/pocketpc/core/storage/StorageRepository.kt")
UPDATER = pathlib.Path("dev/pocketpc/core/update/PocketPcUpdater.kt")

GENERIC_ACTION_VIEW = "Intent.ACTION_VIEW"
ANDROID_PACKAGE_INSTALL = "Intent.ACTION_INSTALL_PACKAGE"
UNKNOWN_SOURCE_SETTINGS = "ACTION_MANAGE_UNKNOWN_APP_SOURCES"


def fail(message: str) -> None:
    raise AssertionError(message)


def scan_occurrences(needle: str) -> dict[pathlib.Path, int]:
    found: dict[pathlib.Path, int] = {}
    for path in SOURCE_ROOT.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        count = text.count(needle)
        if count:
            found[path.relative_to(SOURCE_ROOT)] = count
    return found


def require_exact(
    occurrences: dict[pathlib.Path, int],
    expected: dict[pathlib.Path, int],
    label: str,
) -> None:
    if occurrences != expected:
        actual = ", ".join(
            f"{path}:{count}"
            for path, count in sorted(occurrences.items(), key=lambda item: str(item[0]))
        ) or "none"
        wanted = ", ".join(
            f"{path}:{count}"
            for path, count in sorted(expected.items(), key=lambda item: str(item[0]))
        ) or "none"
        fail(f"{label} boundary changed; expected [{wanted}], found [{actual}]")


def main() -> int:
    kotlin_files = list(SOURCE_ROOT.rglob("*.kt"))
    if not kotlin_files:
        fail("no Kotlin production sources found")

    action_view = scan_occurrences(GENERIC_ACTION_VIEW)
    package_install = scan_occurrences(ANDROID_PACKAGE_INSTALL)
    unknown_sources = scan_occurrences(UNKNOWN_SOURCE_SETTINGS)

    # Generic Android app dispatch is deliberately restricted to one explicit
    # browser menu command labelled as an Android exit. Files and web links may
    # never invoke it automatically.
    require_exact(
        action_view,
        {BROWSER: 1},
        "generic ACTION_VIEW",
    )

    # Android package installation is an explicit OS boundary owned by storage.
    require_exact(
        package_install,
        {STORAGE: 1},
        "Android package installer",
    )

    # Unknown-source settings are legitimate only where APK/update installation
    # is implemented. Any new caller must be reviewed and added intentionally.
    allowed_unknown_sources = {
        STORAGE,
        UPDATER,
    }
    unexpected_unknown_sources = {
        path: count
        for path, count in unknown_sources.items()
        if path not in allowed_unknown_sources
    }
    if unexpected_unknown_sources:
        actual = ", ".join(
            f"{path}:{count}"
            for path, count in sorted(
                unexpected_unknown_sources.items(),
                key=lambda item: str(item[0]),
            )
        )
        fail(f"unknown-source settings escaped allowlist: {actual}")

    browser_text = (SOURCE_ROOT / BROWSER).read_text(encoding="utf-8")
    if '"Abrir fora do PocketPC (Android)"' not in browser_text:
        fail("Browser explicit Android exit is not visibly labelled")
    if "O PocketPC bloqueou a saída automática para o Android" not in browser_text:
        fail("Browser non-http automatic exit blocker is missing")

    storage_text = (SOURCE_ROOT / STORAGE).read_text(encoding="utf-8")
    if "PocketFileRoute.ANDROID_PACKAGE_INSTALLER" not in storage_text:
        fail("Storage APK boundary is not routed through PocketFileRouter")

    print("ANDROID_EXIT_BOUNDARY_POLICY_OK")
    print(f"kotlin_files_scanned={len(kotlin_files)}")
    print("generic_action_view=browser_explicit_only")
    print("generic_file_dispatch_to_android=false")
    print("android_package_install=storage_explicit_only")
    print("unknown_source_settings=install_paths_only")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as error:
        print(f"ANDROID_EXIT_BOUNDARY_POLICY_FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
