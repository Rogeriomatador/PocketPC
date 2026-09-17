#!/usr/bin/env python3
"""Build the pinned Wine x86_64 guest package with experimental PocketPC Vulkan ABI v52.

The official PocketPC Wine build remains v51. This wrapper reuses the existing
pinned builder, swaps only the source preparer to v52, and emits a separate
post-build evidence record. Reaching that record proves package compilation
completed; it does not prove guest runtime, integration, physical visibility,
or Roblox.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BASE_BUILD = ROOT / "scripts/build-wine-x86_64.py"
V52_PREPARER = ROOT / "scripts/prepare-wine-pocketpc-driver-v52.py"
EVIDENCE_NAME = "wine-v52-experimental-build-evidence.json"
MANIFEST_RELATIVE = Path("guest-tool-manifest.json")
CAPABILITY_RELATIVE = Path("share/pocketpc/runtime-graphics-capabilities.json")
WINE_ENTRYPOINT_RELATIVE = Path("bin/wine")
WINDOW_SMOKE_RELATIVE = Path("share/tests/pocketpc-window-smoke.exe")
EXPECTED_GUEST_ROOT = "/opt/pocketpc/wine"
EXPECTED_ENTRYPOINT = WINE_ENTRYPOINT_RELATIVE.as_posix()
CAPABILITY_ID = "pocketpc.vulkan.continuous-present.v52"
POCKETPC_COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def requested_work_dir() -> Path:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--work", type=Path, required=True)
    args, _ = parser.parse_known_args(sys.argv[1:])
    return args.work.resolve()


def pinned_pocketpc_source_revision() -> str:
    raw = os.environ.get("GITHUB_SHA") or os.environ.get("POCKETPC_SOURCE_REVISION") or ""
    revision = raw.strip().lower()
    if POCKETPC_COMMIT_RE.fullmatch(revision) is None:
        raise SystemExit("WINE_V52_POCKETPC_SOURCE_REVISION_NOT_PINNED")
    return revision


def load_base_build():
    spec = importlib.util.spec_from_file_location(
        "pocketpc_build_wine_x86_64_base_v52_experimental",
        BASE_BUILD,
    )
    if spec is None or spec.loader is None:
        raise SystemExit("WINE_V52_BASE_BUILD_IMPORT_FAILED")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def require_bool_false(mapping: dict[str, object], key: str, label: str) -> None:
    if mapping.get(key) is not False:
        raise SystemExit(f"WINE_V52_EVIDENCE_NOT_FAIL_CLOSED:{label}:{key}")


def verify_required_guest_payload(work: Path) -> dict[str, object]:
    package_root = work / "guest-package"
    manifest_path = package_root / MANIFEST_RELATIVE
    package_path = work / "guest-package.zip"
    for path in (package_root, manifest_path, package_path):
        if not path.exists():
            raise SystemExit(f"WINE_V52_REQUIRED_PAYLOAD_INPUT_MISSING:{path.name}")

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("guestRoot") != EXPECTED_GUEST_ROOT:
        raise SystemExit("WINE_V52_GUEST_ROOT_MISMATCH")
    if manifest.get("entrypoint") != EXPECTED_ENTRYPOINT:
        raise SystemExit("WINE_V52_ENTRYPOINT_MISMATCH")

    records: dict[str, dict[str, object]] = {}
    for item in manifest.get("files", []):
        if not isinstance(item, dict):
            raise SystemExit("WINE_V52_MANIFEST_FILE_RECORD_INVALID")
        relative = str(item.get("path", ""))
        if not relative or relative in records:
            raise SystemExit("WINE_V52_MANIFEST_FILE_RECORD_DUPLICATE_OR_EMPTY")
        records[relative] = item

    required = (
        WINE_ENTRYPOINT_RELATIVE,
        WINDOW_SMOKE_RELATIVE,
    )
    local_evidence: dict[str, dict[str, object]] = {}
    for relative_path in required:
        relative = relative_path.as_posix()
        record = records.get(relative)
        if record is None:
            raise SystemExit(f"WINE_V52_REQUIRED_MANIFEST_RECORD_MISSING:{relative}")
        source = package_root / relative_path
        if not source.is_file() or source.stat().st_size <= 0:
            raise SystemExit(f"WINE_V52_REQUIRED_PACKAGE_FILE_MISSING:{relative}")
        digest = sha256(source)
        if record.get("bytes") != source.stat().st_size:
            raise SystemExit(f"WINE_V52_REQUIRED_FILE_SIZE_MISMATCH:{relative}")
        if record.get("sha256") != digest:
            raise SystemExit(f"WINE_V52_REQUIRED_FILE_DIGEST_MISMATCH:{relative}")
        local_evidence[relative] = {
            "path": relative,
            "bytes": source.stat().st_size,
            "sha256": digest,
        }

    with zipfile.ZipFile(package_path, "r") as archive:
        names = set(archive.namelist())
        if MANIFEST_RELATIVE.as_posix() not in names:
            raise SystemExit("WINE_V52_ZIP_MANIFEST_MISSING")
        archived_manifest = json.loads(
            archive.read(MANIFEST_RELATIVE.as_posix()).decode("utf-8")
        )
        if archived_manifest != manifest:
            raise SystemExit("WINE_V52_ZIP_MANIFEST_CONTENT_MISMATCH")
        for relative_path in required:
            relative = relative_path.as_posix()
            if relative not in names:
                raise SystemExit(f"WINE_V52_REQUIRED_ZIP_FILE_MISSING:{relative}")
            archived = archive.read(relative)
            evidence = local_evidence[relative]
            if len(archived) != evidence["bytes"]:
                raise SystemExit(f"WINE_V52_REQUIRED_ZIP_SIZE_MISMATCH:{relative}")
            if sha256_bytes(archived) != evidence["sha256"]:
                raise SystemExit(f"WINE_V52_REQUIRED_ZIP_DIGEST_MISMATCH:{relative}")

    return {
        "guestRoot": EXPECTED_GUEST_ROOT,
        "entrypoint": EXPECTED_ENTRYPOINT,
        "wineEntrypoint": local_evidence[WINE_ENTRYPOINT_RELATIVE.as_posix()],
        "windowSmokeFixture": local_evidence[WINDOW_SMOKE_RELATIVE.as_posix()],
    }


def attach_verified_v52_capability(work: Path) -> tuple[str, dict[str, object]]:
    pocketpc_source_revision = pinned_pocketpc_source_revision()
    package_root = work / "guest-package"
    manifest_path = package_root / MANIFEST_RELATIVE
    package_path = work / "guest-package.zip"
    base_path = work / "wine-build-evidence.json"
    for path in (package_root, manifest_path, package_path, base_path):
        if not path.exists():
            raise SystemExit(f"WINE_V52_CAPABILITY_INPUT_MISSING:{path.name}")

    capability_path = package_root / CAPABILITY_RELATIVE
    capability_path.parent.mkdir(parents=True, exist_ok=True)
    capability = {
        "schemaVersion": 1,
        "wineVulkanAbi": 52,
        "capabilities": [CAPABILITY_ID],
        "pocketPcSourceRevision": pocketpc_source_revision,
        "experimental": True,
        "officialBuildSelected": False,
        "runtimeExecuted": False,
        "integrationExecuted": False,
        "physicalVisibleFrame": False,
        "robloxExecuted": False,
    }
    capability_path.write_text(
        json.dumps(capability, indent=2) + "\n",
        encoding="utf-8",
    )

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    files = [
        item
        for item in manifest.get("files", [])
        if item.get("path") != CAPABILITY_RELATIVE.as_posix()
    ]
    files.append(
        {
            "path": CAPABILITY_RELATIVE.as_posix(),
            "bytes": capability_path.stat().st_size,
            "sha256": sha256(capability_path),
            "executable": False,
        }
    )
    files.sort(key=lambda item: str(item["path"]))
    manifest["files"] = files
    manifest_path.write_text(
        json.dumps(manifest, indent=2) + "\n",
        encoding="utf-8",
    )

    with zipfile.ZipFile(
        package_path,
        "w",
        compression=zipfile.ZIP_DEFLATED,
        compresslevel=9,
    ) as archive:
        ordered = [
            (MANIFEST_RELATIVE.as_posix(), False),
            *[
                (str(item["path"]), bool(item["executable"]))
                for item in files
            ],
        ]
        for relative, executable in ordered:
            source = package_root / relative
            if not source.is_file():
                raise SystemExit(f"WINE_V52_PACKAGE_FILE_MISSING:{relative}")
            info = zipfile.ZipInfo(relative, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = (
                (0o100755 if executable else 0o100644) << 16
            )
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, source.read_bytes())

    required_payload = verify_required_guest_payload(work)

    base = json.loads(base_path.read_text(encoding="utf-8"))
    package = base.setdefault("package", {})
    package["fileCount"] = len(files)
    package["manifestSha256"] = sha256(manifest_path)
    package["zipBytes"] = package_path.stat().st_size
    package["zipSha256"] = sha256(package_path)
    package["verifiedRequiredPayload"] = required_payload
    base_path.write_text(
        json.dumps(base, indent=2) + "\n",
        encoding="utf-8",
    )
    return pocketpc_source_revision, required_payload


def emit_post_build_evidence(
    work: Path,
    pocketpc_source_revision: str,
    required_payload: dict[str, object],
) -> None:
    base_path = work / "wine-build-evidence.json"
    overlay_path = work / "wine-pocketpc-driver-overlay-evidence.json"
    package_path = work / "guest-package.zip"
    for path in (base_path, overlay_path, package_path):
        if not path.is_file():
            raise SystemExit(f"WINE_V52_POST_BUILD_EVIDENCE_MISSING:{path.name}")

    base = json.loads(base_path.read_text(encoding="utf-8"))
    overlay = json.loads(overlay_path.read_text(encoding="utf-8"))

    if base.get("status") != "WINE_X86_64_WITH_POCKETPC_DRIVER_COMPILED_PACKAGE_NOT_GUEST_TESTED_NOT_APPROVED":
        raise SystemExit("WINE_V52_BASE_BUILD_STATUS_MISMATCH")
    if overlay.get("privateWineVulkanAbi") != 52:
        raise SystemExit("WINE_V52_OVERLAY_ABI_MISMATCH")
    if overlay.get("derivedFromPrivateAbi") != 51:
        raise SystemExit("WINE_V52_OVERLAY_BASE_ABI_MISMATCH")
    require_bool_false(overlay, "officialBuildSelected", "overlay")
    require_bool_false(overlay, "runtimeExecuted", "overlay")
    require_bool_false(overlay, "integrationExecuted", "overlay")
    require_bool_false(overlay, "physicalVisibleFrame", "overlay")
    require_bool_false(overlay, "robloxExecuted", "overlay")
    require_bool_false(overlay, "robloxRendered", "overlay")
    require_bool_false(overlay, "robloxPlayable", "overlay")

    evidence = {
        "schemaVersion": 1,
        "status": "EXPERIMENTAL_WINE_V52_COMPILED_PACKAGE_NOT_RUNTIME_TESTED_NOT_APPROVED",
        "targetPrivateWineVulkanAbi": 52,
        "derivedFromPrivateWineVulkanAbi": 51,
        "officialBuildSelected": False,
        "buildExecuted": True,
        "packageCompiled": True,
        "baseBuildEvidenceStatus": base["status"],
        "sourceCommit": base.get("sourceCommit"),
        "pocketPcSourceRevision": pocketpc_source_revision,
        "package": {
            "path": package_path.name,
            "bytes": package_path.stat().st_size,
            "sha256": sha256(package_path),
        },
        "verifiedRequiredGuestPayload": required_payload,
        "verifiedRuntimeGraphicsCapability": {
            "path": CAPABILITY_RELATIVE.as_posix(),
            "sha256": sha256(work / "guest-package" / CAPABILITY_RELATIVE),
            "wineVulkanAbi": 52,
            "capabilities": [CAPABILITY_ID],
            "pocketPcSourceRevision": pocketpc_source_revision,
        },
        "evidenceInputs": {
            "wineBuildEvidenceSha256": sha256(base_path),
            "v52OverlayEvidenceSha256": sha256(overlay_path),
        },
        "runtimeExecuted": False,
        "integrationExecuted": False,
        "physicalVisibleFrame": False,
        "robloxExecuted": False,
        "robloxRendered": False,
        "robloxPlayable": False,
        "classification": {
            "build": "SOFTWARE_BUILD_EXECUTED",
            "runtime": "NOT_EXECUTED",
            "integration": "NOT_EXECUTED",
            "physical": "NOT_EXECUTED",
            "roblox": "NOT_EXECUTED",
        },
        "limitations": [
            "The Wine entrypoint and GDI window smoke fixture were verified inside the package by manifest path, size, and SHA-256 only; they were not executed.",
            "A successful Wine package build does not prove the v52 guest was loaded.",
            "No continuous-present frame is claimed from build evidence.",
            "No Android integration or physical-visible frame is claimed.",
            "No Roblox execution, rendering, playability, input, audio, network, or stability is claimed.",
        ],
    }
    output = work / EVIDENCE_NAME
    output.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(f"POCKETPC_WINE_V52_EXPERIMENTAL_BUILD_EVIDENCE={output}")
    print(f"POCKETPC_SOURCE_REVISION={pocketpc_source_revision}")
    print("V52_REQUIRED_GUEST_PAYLOAD_VERIFIED=1")
    print("V52_BUILD_EXECUTED=1")
    print("V52_RUNTIME_EXECUTED=0")
    print("V52_PHYSICAL_VISIBLE_FRAME=0")
    print("V52_ROBLOX_EXECUTED=0")


def main() -> int:
    if not BASE_BUILD.is_file():
        raise SystemExit(f"WINE_V52_BASE_BUILD_MISSING:{BASE_BUILD}")
    if not V52_PREPARER.is_file():
        raise SystemExit(f"WINE_V52_PREPARER_MISSING:{V52_PREPARER}")

    work = requested_work_dir()
    module = load_base_build()
    module.DRIVER_PREPARER = V52_PREPARER
    result = int(module.main() or 0)
    if result != 0:
        return result
    pocketpc_source_revision, required_payload = attach_verified_v52_capability(work)
    emit_post_build_evidence(
        work,
        pocketpc_source_revision,
        required_payload,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
