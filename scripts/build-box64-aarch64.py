#!/usr/bin/env python3
"""Build pinned Box64 for Linux AArch64 as a review-only artifact.

The result is intended for the PocketPC Linux guest, not as an Android JNI
library. It is never copied into app/src and is never marked runtime-ready.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[1]
LOCK_PATH = ROOT / "third_party/box64/LOCK.json"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while block := f.read(1024 * 1024):
            h.update(block)
    return h.hexdigest()


def run(argv: list[str], cwd: Path, log: Path) -> None:
    with log.open("w", encoding="utf-8") as out:
        result = subprocess.run(argv, cwd=cwd, stdout=out, stderr=subprocess.STDOUT, text=True)
    if result.returncode:
        raise RuntimeError(f"command failed ({result.returncode}): {' '.join(argv)}")


def elf_header(path: Path) -> tuple[int, int, int]:
    raw = path.read_bytes()[:20]
    if len(raw) < 20 or raw[:4] != b"\x7fELF":
        raise ValueError("Box64 output is not ELF")
    elf_class = raw[4]
    endian = raw[5]
    if endian != 1:
        raise ValueError("Box64 output is not little-endian")
    elf_type, machine = struct.unpack_from("<HH", raw, 16)
    return elf_class, elf_type, machine


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--work", type=Path, required=True)
    p.add_argument("--compiler", default="aarch64-linux-gnu-gcc")
    args = p.parse_args()

    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    work = args.work.resolve()
    if work == ROOT.resolve() or ROOT.resolve() in work.parents:
        raise SystemExit("BOX64_WORK_MUST_BE_OUTSIDE_REPOSITORY")
    work.mkdir(parents=True, exist_ok=False)

    source = work / "source"
    build = work / "build"
    quarantine = work / "quarantine"
    package_root = work / "guest-package"
    package_bin = package_root / "bin"
    quarantine.mkdir()
    package_bin.mkdir(parents=True)

    run(["git", "init", str(source)], work, work / "git-init.log")
    run(["git", "-C", str(source), "remote", "add", "origin", lock["repository"]], work, work / "git-remote.log")
    run(["git", "-C", str(source), "fetch", "--depth", "1", "origin", lock["commit"]], work, work / "git-fetch.log")
    run(["git", "-C", str(source), "checkout", "--detach", "FETCH_HEAD"], work, work / "git-checkout.log")

    actual_commit = subprocess.check_output(
        ["git", "-C", str(source), "rev-parse", "HEAD"], text=True
    ).strip()
    if actual_commit != lock["commit"]:
        raise SystemExit("BOX64_SOURCE_COMMIT_MISMATCH")

    license_text = (source / "LICENSE").read_text(encoding="utf-8")
    if not license_text.startswith("MIT License"):
        raise SystemExit("BOX64_LICENSE_EVIDENCE_MISMATCH")

    cmake = [
        "cmake", "-S", str(source), "-B", str(build),
        "-DARM64=1",
        "-DBAD_SIGNAL=ON",
        "-DBOX32=OFF",
        "-DWOW64=OFF",
        "-DCMAKE_SYSTEM_NAME=Linux",
        f"-DCMAKE_C_COMPILER={args.compiler}",
        "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
    ]
    run(cmake, work, work / "cmake-configure.log")
    run(["cmake", "--build", str(build), "--target", "box64", "-j2"], work, work / "cmake-build.log")

    candidate = build / "box64"
    if not candidate.is_file():
        raise SystemExit("BOX64_ARTIFACT_MISSING")
    elf_class, elf_type, machine = elf_header(candidate)
    if elf_class != 2 or machine != 183 or elf_type not in (2, 3):
        raise SystemExit(
            f"BOX64_ELF_TARGET_MISMATCH class={elf_class} type={elf_type} machine={machine}"
        )

    staged = quarantine / "box64"
    packaged = package_bin / "box64"
    shutil.copy2(candidate, staged)
    shutil.copy2(candidate, packaged)
    if sha256(staged) != sha256(candidate) or sha256(packaged) != sha256(candidate):
        raise SystemExit("BOX64_COPY_DIGEST_MISMATCH")

    guest_manifest = {
        "schemaVersion": 1,
        "id": "box64",
        "version": lock["version"],
        "architecture": "aarch64",
        "guestRoot": "/opt/pocketpc/box64",
        "entrypoint": "bin/box64",
        "sourceCommit": actual_commit,
        "license": lock["license"],
        "files": [
            {
                "path": "bin/box64",
                "bytes": packaged.stat().st_size,
                "sha256": sha256(packaged),
                "executable": True,
            }
        ],
    }
    (package_root / "guest-tool-manifest.json").write_text(
        json.dumps(guest_manifest, indent=2) + "\\n", encoding="utf-8"
    )

    evidence = {
        "schemaVersion": 1,
        "status": "COMPILED_ELF_AARCH64_NOT_GUEST_TESTED_NOT_APPROVED",
        "version": lock["version"],
        "sourceCommit": actual_commit,
        "sourceLockSha256": sha256(LOCK_PATH),
        "artifact": {
            "fileName": staged.name,
            "bytes": staged.stat().st_size,
            "sha256": sha256(staged),
            "guestPackageManifestSha256": sha256(package_root / "guest-tool-manifest.json"),
            "elfClass": elf_class,
            "elfType": elf_type,
            "machine": machine,
        },
        "notExecuted": [
            "PocketPC guest-tool package installation",
            "Box64 --version inside PocketPC rootfs",
            "x86_64 ELF execution",
            "Wine",
            "Windows application",
            "Roblox",
        ],
    }
    (work / "box64-build-evidence.json").write_text(
        json.dumps(evidence, indent=2) + "\n", encoding="utf-8"
    )
    print("BOX64_AARCH64_BUILD_READY_FOR_REVIEW_NOT_RUNTIME_TESTED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
