#!/usr/bin/env python3
from pathlib import Path

source = Path("app/src/main/java/dev/pocketpc/core/storage/PocketDownloadImporter.kt").read_text(encoding="utf-8")

required = [
    "WINDOWS_EXECUTABLE_EXTENSIONS",
    "MZ",
    ".bin",
    ".exe",
]

missing = [item for item in required if item not in source]
if missing:
    raise SystemExit(f"POCKET_DOWNLOAD_IMPORTER_POLICY_FAIL missing={missing}")

print("POCKET_DOWNLOAD_IMPORTER_POLICY_PASS")
