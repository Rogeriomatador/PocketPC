#!/usr/bin/env python3
"""Build pinned Box64 for Linux AArch64 and emit a review-only guest package."""
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
LOCK_PATH = ROOT / "third_party/box64/LOCK.json"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while block := f.read(1024 * 1024):
            h.update(block)
    return h.hexdigest()


def run(argv: list[str], cwd: Path, log: Path) -> None:
    with log.open("w", encoding="utf-8") as out:
        result = subprocess.run(
            argv,
            cwd=cwd,
            stdout=out,
            stderr=subprocess.STDOUT,
            text=True,
        )
    if result.returncode:
        raise RuntimeError(
            f"command failed ({result.returncode}): {' '.join(argv)}"
        )


def elf_header(path: Path) -> tuple[int, int, int]:
    raw = path.read_bytes()[:20]
    if len(raw) < 20 or raw[:4] != b"\x7fELF":
        raise ValueError(f"{path.name} is not ELF")
    elf_class = raw[4]
    endian = raw[5]
    if endian != 1:
        raise ValueError(f"{path.name} is not little-endian")
    elf_type, machine = struct.unpack_from("<HH", raw, 16)
    return elf_class, elf_type, machine


def deterministic_zip(zip_path: Path, package_root: Path) -> None:
    entries = (
        ("guest-tool-manifest.json", 0o100644),
        ("bin/box64", 0o100755),
        ("share/tests/box64-smoke-x86_64", 0o100755),
        ("share/tests/display-bridge-smoke-x86_64", 0o100755),
    )
    with zipfile.ZipFile(
        zip_path,
        "w",
        compression=zipfile.ZIP_DEFLATED,
        compresslevel=9,
    ) as archive:
        for relative, mode in entries:
            source = package_root / relative
            info = zipfile.ZipInfo(relative, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = mode << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, source.read_bytes())


def build_x86_64_smoke(work: Path) -> Path:
    source = work / "box64-smoke.S"
    output = work / "box64-smoke-x86_64"
    source.write_text(
        """.global _start
.section .text
_start:
    mov $1, %rax
    mov $1, %rdi
    lea message(%rip), %rsi
    mov $24, %rdx
    syscall
    mov $60, %rax
    xor %rdi, %rdi
    syscall
.section .rodata
message:
    .ascii "POCKETPC_BOX64_SMOKE_OK\\n"
""",
        encoding="utf-8",
    )
    run(
        [
            "cc",
            "-nostdlib",
            "-static",
            "-Wl,--build-id=none",
            "-Wl,-z,noexecstack",
            "-o",
            str(output),
            str(source),
        ],
        work,
        work / "smoke-build.log",
    )
    elf_class, elf_type, machine = elf_header(output)
    if elf_class != 2 or machine != 62 or elf_type not in (2, 3):
        raise SystemExit(
            "BOX64_SMOKE_ELF_TARGET_MISMATCH "
            f"class={elf_class} type={elf_type} machine={machine}"
        )
    return output


def build_display_bridge_smoke(work: Path) -> Path:
    bridge_source = (
        ROOT /
        "third_party/wine/pocketpc-display-bridge"
    )
    header = bridge_source / "pocketpc_display_bridge.h"
    transport = bridge_source / "pocketpc_display_bridge.c"
    smoke_source = bridge_source / "display_bridge_smoke.c"
    for required in (header, transport, smoke_source):
        if not required.is_file():
            raise SystemExit(
                "DISPLAY_BRIDGE_SOURCE_MISSING:" +
                required.name
            )

    output = work / "display-bridge-smoke-x86_64"
    run(
        [
            "cc",
            "-static",
            "-Os",
            "-s",
            "-Wl,--build-id=none",
            "-Wl,-z,noexecstack",
            "-I",
            str(bridge_source),
            "-o",
            str(output),
            str(transport),
            str(smoke_source),
        ],
        work,
        work / "display-bridge-smoke-build.log",
    )

    elf_class, elf_type, machine = elf_header(output)
    if elf_class != 2 or machine != 62 or elf_type not in (2, 3):
        raise SystemExit(
            "DISPLAY_BRIDGE_SMOKE_ELF_TARGET_MISMATCH "
            f"class={elf_class} type={elf_type} machine={machine}"
        )
    return output

def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--work", type=Path, required=True)
    p.add_argument("--compiler", default="aarch64-linux-gnu-gcc")
    args = p.parse_args()

    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    work = args.work.resolve()
    repo = ROOT.resolve()
    if work == repo or repo in work.parents:
        raise SystemExit("BOX64_WORK_MUST_BE_OUTSIDE_REPOSITORY")
    work.mkdir(parents=True, exist_ok=False)

    source = work / "source"
    build = work / "build"
    quarantine = work / "quarantine"
    package_root = work / "guest-package"
    package_bin = package_root / "bin"
    package_tests = package_root / "share/tests"
    quarantine.mkdir()
    package_bin.mkdir(parents=True)
    package_tests.mkdir(parents=True)

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

    actual_commit = subprocess.check_output(
        ["git", "-C", str(source), "rev-parse", "HEAD"],
        text=True,
    ).strip()
    if actual_commit != lock["commit"]:
        raise SystemExit("BOX64_SOURCE_COMMIT_MISMATCH")

    license_text = (source / "LICENSE").read_text(encoding="utf-8")
    if not license_text.startswith("MIT License"):
        raise SystemExit("BOX64_LICENSE_EVIDENCE_MISMATCH")

    run(
        [
            "cmake",
            "-S",
            str(source),
            "-B",
            str(build),
            "-DARM64=1",
            "-DBAD_SIGNAL=ON",
            "-DBOX32=OFF",
            "-DWOW64=OFF",
            "-DCMAKE_SYSTEM_NAME=Linux",
            f"-DCMAKE_C_COMPILER={args.compiler}",
            "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
        ],
        work,
        work / "cmake-configure.log",
    )
    run(
        ["cmake", "--build", str(build), "--target", "box64", "-j2"],
        work,
        work / "cmake-build.log",
    )

    candidate = build / "box64"
    if not candidate.is_file():
        raise SystemExit("BOX64_ARTIFACT_MISSING")
    elf_class, elf_type, machine = elf_header(candidate)
    if elf_class != 2 or machine != 183 or elf_type not in (2, 3):
        raise SystemExit(
            "BOX64_ELF_TARGET_MISMATCH "
            f"class={elf_class} type={elf_type} machine={machine}"
        )

    smoke = build_x86_64_smoke(work)
    display_bridge_smoke =
        build_display_bridge_smoke(work)
    staged = quarantine / "box64"
    packaged = package_bin / "box64"
    packaged_smoke = package_tests / "box64-smoke-x86_64"
    packaged_bridge_smoke =
        package_tests / "display-bridge-smoke-x86_64"
    shutil.copy2(candidate, staged)
    shutil.copy2(candidate, packaged)
    shutil.copy2(smoke, packaged_smoke)
    shutil.copy2(
        display_bridge_smoke,
        packaged_bridge_smoke,
    )

    if sha256(staged) != sha256(candidate) or sha256(packaged) != sha256(candidate):
        raise SystemExit("BOX64_COPY_DIGEST_MISMATCH")
    if sha256(packaged_smoke) != sha256(smoke):
        raise SystemExit("BOX64_SMOKE_COPY_DIGEST_MISMATCH")
    if (
        sha256(packaged_bridge_smoke) !=
        sha256(display_bridge_smoke)
    ):
        raise SystemExit(
            "DISPLAY_BRIDGE_SMOKE_COPY_DIGEST_MISMATCH"
        )

    guest_manifest = {
        "schemaVersion": 1,
        "id": "box64",
        "version": lock["version"],
        "architecture": "aarch64",
        "executionMode": "native-aarch64",
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
            },
            {
                "path": "share/tests/box64-smoke-x86_64",
                "bytes": packaged_smoke.stat().st_size,
                "sha256": sha256(packaged_smoke),
                "executable": True,
            },
            {
                "path": "share/tests/display-bridge-smoke-x86_64",
                "bytes": packaged_bridge_smoke.stat().st_size,
                "sha256": sha256(packaged_bridge_smoke),
                "executable": True,
            },
        ],
    }
    manifest_path = package_root / "guest-tool-manifest.json"
    manifest_path.write_text(
        json.dumps(guest_manifest, indent=2) + "\n",
        encoding="utf-8",
    )

    zip_path = work / "guest-package.zip"
    deterministic_zip(zip_path, package_root)

    evidence = {
        "schemaVersion": 1,
        "status": "COMPILED_ELF_AARCH64_WITH_X86_64_SMOKE_NOT_GUEST_TESTED_NOT_APPROVED",
        "version": lock["version"],
        "sourceCommit": actual_commit,
        "sourceLockSha256": sha256(LOCK_PATH),
        "artifact": {
            "fileName": staged.name,
            "bytes": staged.stat().st_size,
            "sha256": sha256(staged),
            "elfClass": elf_class,
            "elfType": elf_type,
            "machine": machine,
        },
        "smokeArtifact": {
            "fileName": packaged_smoke.name,
            "bytes": packaged_smoke.stat().st_size,
            "sha256": sha256(packaged_smoke),
            "machine": 62,
            "expectedOutput": "POCKETPC_BOX64_SMOKE_OK",
        },
        "displayBridgeSources": {
            "headerSha256": sha256(
                ROOT /
                "third_party/wine/pocketpc-display-bridge/pocketpc_display_bridge.h"
            ),
            "transportSha256": sha256(
                ROOT /
                "third_party/wine/pocketpc-display-bridge/pocketpc_display_bridge.c"
            ),
            "smokeSha256": sha256(
                ROOT /
                "third_party/wine/pocketpc-display-bridge/display_bridge_smoke.c"
            ),
        },
        "displayBridgeSmokeArtifact": {
            "fileName": packaged_bridge_smoke.name,
            "bytes": packaged_bridge_smoke.stat().st_size,
            "sha256": sha256(packaged_bridge_smoke),
            "machine": 62,
            "protocolVersion": 2,
            "expectedOutput": "POCKETPC_DISPLAY_BRIDGE_SMOKE_OK",
        },
        "guestPackage": {
            "manifestSha256": sha256(manifest_path),
            "zipBytes": zip_path.stat().st_size,
            "zipSha256": sha256(zip_path),
        },
        "notExecuted": [
            "PocketPC guest-tool package installation",
            "Box64 --version inside PocketPC rootfs",
            "x86_64 smoke execution through Box64",
            "x86_64 display bridge handshake through Box64",
            "Wine",
            "Windows application",
            "Roblox",
        ],
    }
    (work / "box64-build-evidence.json").write_text(
        json.dumps(evidence, indent=2) + "\n",
        encoding="utf-8",
    )
    print("BOX64_AARCH64_BUILD_READY_FOR_REVIEW_NOT_RUNTIME_TESTED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
