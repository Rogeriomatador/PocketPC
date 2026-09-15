#!/usr/bin/env python3
from pathlib import Path

source_path = Path("app/src/main/java/dev/pocketpc/core/storage/PocketDownloadImporter.kt")
source = source_path.read_text(encoding="utf-8")

required = {
    "windows extension set": "WINDOWS_EXECUTABLE_EXTENSIONS",
    "PE/MZ probe": "MZ",
    "bin fallback": '"bin"',
    "exe normalization": '"exe"',
}

missing = [label for label, token in required.items() if token not in source]
if missing:
    raise SystemExit(
        "POCKET_DOWNLOAD_IMPORTER_POLICY_FAIL missing=" + ",".join(missing)
    )

print("POCKET_DOWNLOAD_IMPORTER_POLICY_PASS")
