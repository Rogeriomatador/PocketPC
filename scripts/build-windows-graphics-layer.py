#!/usr/bin/env python3
"""Build a pinned PocketPC Windows graphics layer as review-only PE64 DLLs."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LOCK_PATH = ROOT / "third_party/graphics/LOCK.json"

CONFIG = {
    "dxvk": {
        "lockKey": "dxvk",
        "license": "Zlib",
        "licenseSentinel": "zlib/libpng license",
        "allowed": {
            "d3d8.dll",
            "d3d9.dll",
            "d3d10core.dll",
            "d3d11.dll",
            "dxgi.dll",
        },
        "required": {"d3d11.dll", "dxgi.dll"},
        "crossFile": "build-win64.txt",
        "mesonArgs": [],
    },
    "vkd3d-proton": {
        "lockKey": "vkd3dProton",
        "license": "LGPL-2.1-or-later",
        "licenseSentinel": "GNU LESSER GENERAL PUBLIC LICENSE",
        "allowed": {"d3d12.dll", "d3d12core.dll"},
        "required": {"d3d12.dll"},
        "crossFile": "build-win64.txt",
        "mesonArgs": [
            "-Denable_tests=false",
            "-Denable_extras=false",
            "-Denable_trace=false",
        ],
    },
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def run(argv: list[str], cwd: Path, log: Path) -> None:
    with log.open("w", encoding="utf-8") as output:
        result = subprocess.run(
            argv,
            cwd=cwd,
            stdout=output,
            stderr=subprocess.STDOUT,
            text=True,
        )
    if result.returncode:
        raise RuntimeError(
            f"command failed ({result.returncode}): {' '.join(argv)}"
        )


def pe_machine(path: Path) -> tuple[int, int]:
    raw = path.read_bytes()
    if len(raw) < 0x40 or raw[:2] != b"MZ":
        raise ValueError(f"{path.name} is not PE")
    pe_offset = struct.unpack_from("<I", raw, 0x3C)[0]
    if (
        pe_offset + 26 > len(raw)
        or raw[pe_offset:pe_offset + 4] != b"PE\x00\x00"
    ):
        raise ValueError(f"{path.name} PE signature invalid")
    machine = struct.unpack_from("<H", raw, pe_offset + 4)[0]
    optional_magic = struct.unpack_from("<H", raw, pe_offset + 24)[0]
    return machine, optional_magic


def deterministic_zip(
    zip_path: Path,
    package_root: Path,
    files: list[dict[str, object]],
) -> None:
    with zipfile.ZipFile(
        zip_path,
        "w",
        compression=zipfile.ZIP_DEFLATED,
        compresslevel=9,
    ) as archive:
        ordered = [
            ("windows-layer-manifest.json", False),
            *[(str(item["path"]), False) for item in files],
        ]
        for relative, executable in ordered:
            source = package_root / relative
            info = zipfile.ZipInfo(relative, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = (
                0o100755 if executable else 0o100644
            ) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, source.read_bytes())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--component",
        choices=sorted(CONFIG),
        required=True,
    )
    parser.add_argument("--work", type=Path, required=True)
    args = parser.parse_args()

    config = CONFIG[args.component]
    locks = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    lock = locks[config["lockKey"]]

    work = args.work.resolve()
    repo = ROOT.resolve()
    if work == repo or repo in work.parents:
        raise SystemExit("WINDOWS_LAYER_WORK_MUST_BE_OUTSIDE_REPOSITORY")
    work.mkdir(parents=True, exist_ok=False)

    source = work / "source"
    build = work / "build"
    install = work / "install"
    package = work / "windows-layer-package"
    dll_dir = package / "dll"
    dll_dir.mkdir(parents=True)

    run(["git", "init", str(source)], work, work / "git-init.log")
    run(
        ["git", "-C", str(source), "remote", "add", "origin", lock["repository"]],
        work,
        work / "git-remote.log",
    )
    run(
        ["git", "-C", str(source), "fetch", "--depth", "1", "origin", lock["commit"]],
        work,
        work / "git-fetch.log",
    )
    run(
        ["git", "-C", str(source), "checkout", "--detach", "FETCH_HEAD"],
        work,
        work / "git-checkout.log",
    )
    run(
        ["git", "-C", str(source), "submodule", "update", "--init", "--recursive", "--depth", "1"],
        work,
        work / "git-submodules.log",
    )

    actual = subprocess.check_output(
        ["git", "-C", str(source), "rev-parse", "HEAD"],
        text=True,
    ).strip()
    if actual != lock["commit"]:
        raise SystemExit("WINDOWS_LAYER_SOURCE_COMMIT_MISMATCH")

    license_text = (source / "LICENSE").read_text(
        encoding="utf-8",
        errors="replace",
    )
    if config["licenseSentinel"] not in license_text:
        raise SystemExit("WINDOWS_LAYER_LICENSE_EVIDENCE_MISMATCH")

    meson = [
        "meson",
        "setup",
        "--cross-file",
        str(source / config["crossFile"]),
        "--buildtype",
        "release",
        "--prefix",
        str(install),
        *config["mesonArgs"],
        str(build),
        str(source),
    ]
    run(meson, work, work / "meson-setup.log")
    run(
        ["ninja", "-C", str(build), "install"],
        work,
        work / "ninja-install.log",
    )

    candidates: dict[str, Path] = {}
    for dll in install.rglob("*.dll"):
        name = dll.name.lower()
        if name in config["allowed"]:
            if name in candidates:
                raise SystemExit(
                    "WINDOWS_LAYER_DUPLICATE_OUTPUT:" + name
                )
            candidates[name] = dll

    missing = sorted(config["required"] - set(candidates))
    if missing:
        raise SystemExit(
            "WINDOWS_LAYER_REQUIRED_OUTPUT_MISSING:" + ",".join(missing)
        )

    unexpected = sorted(
        dll.name.lower()
        for dll in install.rglob("*.dll")
        if dll.name.lower() not in config["allowed"]
    )
    if unexpected:
        raise SystemExit(
            "WINDOWS_LAYER_UNEXPECTED_DLL_OUTPUT:" + ",".join(unexpected)
        )

    records: list[dict[str, object]] = []
    for name, source_dll in sorted(candidates.items()):
        machine, optional_magic = pe_machine(source_dll)
        if machine != 0x8664 or optional_magic != 0x20B:
            raise SystemExit(
                "WINDOWS_LAYER_DLL_TARGET_MISMATCH:"
                + name
                + f":machine=0x{machine:04x}:optional=0x{optional_magic:04x}"
            )

        relative = Path("dll") / name
        destination = package / relative
        shutil.copyfile(source_dll, destination)
        destination.chmod(0o644)
        records.append(
            {
                "path": relative.as_posix(),
                "destinationName": name,
                "bytes": destination.stat().st_size,
                "sha256": sha256(destination),
            }
        )

    manifest = {
        "schemaVersion": 1,
        "id": args.component,
        "version": lock["version"],
        "sourceCommit": actual,
        "license": config["license"],
        "windowsArchitecture": "x86_64-windows",
        "targetDirectory": "drive_c/windows/system32",
        "files": records,
    }
    manifest_path = package / "windows-layer-manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, indent=2) + "\n",
        encoding="utf-8",
    )

    zip_path = work / "windows-layer-package.zip"
    deterministic_zip(zip_path, package, records)

    evidence = {
        "schemaVersion": 1,
        "status": "PE64_DLL_LAYER_COMPILED_NOT_WINE_DEPLOYED_NOT_RUNTIME_TESTED",
        "component": args.component,
        "version": lock["version"],
        "sourceCommit": actual,
        "graphicsLockSha256": sha256(LOCK_PATH),
        "files": records,
        "package": {
            "manifestSha256": sha256(manifest_path),
            "zipBytes": zip_path.stat().st_size,
            "zipSha256": sha256(zip_path),
        },
        "notExecuted": [
            "PocketPC graphics-layer staging",
            "Wine prefix deployment",
            "Vulkan guest presentation",
            "Direct3D smoke test",
            "Roblox",
        ],
    }
    (work / "windows-layer-build-evidence.json").write_text(
        json.dumps(evidence, indent=2) + "\n",
        encoding="utf-8",
    )
    print(
        "WINDOWS_GRAPHICS_LAYER_READY_FOR_REVIEW_NOT_RUNTIME_TESTED"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
