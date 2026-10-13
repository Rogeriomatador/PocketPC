#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
source_path = ROOT / "app/src/main/java/dev/pocketpc/core/storage/PocketDownloadBridge.kt"
source = source_path.read_text(encoding="utf-8")

required = {
    "download normalization": "normalizeDownloadedPayloadName(",
    "PE/MZ signature bytes": "firstByte == 0x4d && secondByte == 0x5a",
    "bin fallback guard": 'extension != "bin" || !mzExecutable',
    "exe normalization": 'sanitized.dropLast(4) + ".exe"',
}

missing = [label for label, token in required.items() if token not in source]
if missing:
    raise SystemExit(
        "POCKET_DOWNLOAD_IMPORTER_POLICY_FAIL missing=" + ",".join(missing)
    )

print("POCKET_DOWNLOAD_IMPORTER_POLICY_PASS")
