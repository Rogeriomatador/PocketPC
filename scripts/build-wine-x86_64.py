#!/usr/bin/env python3
"""Build pinned Wine 11.0 x86_64 headless and emit a review-only guest package."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LOCK_PATH = ROOT / "third_party/wine/LOCK.json"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while block := f.read(1024 * 1024):
            h.update(block)
    return h.hexdigest()


def run(argv: list[str], cwd: Path, log: Path, env: dict[str, str] | None = None) -> None:
    merged = os.environ.copy()
    if env:
        merged.update(env)
    with log.open("w", encoding="utf-8") as out:
        result = subprocess.run(
            argv,
            cwd=cwd,
            stdout=out,
            stderr=subprocess.STDOUT,
            text=True,
            env=merged,
        )
    if result.returncode:
        raise RuntimeError(
            f"command failed ({result.returncode}): {' '.join(argv)}"
        )


def elf_header(path: Path) -> tuple[int, int, int]:
    raw = path.read_bytes()[:20]
    if len(raw) < 20 or raw[:4] != b"\x7fELF":
        raise ValueError(f"{path.name} is not ELF")
    if raw[5] != 1:
        raise ValueError(f"{path.name} is not little-endian")
    elf_type, machine = struct.unpack_from("<HH", raw, 16)
    return raw[4], elf_type, machine


def pe_machine(path: Path) -> tuple[int, int]:
    raw = path.read_bytes()
    if len(raw) < 0x40 or raw[:2] != b"MZ":
        raise ValueError("Win64 smoke is not PE")
    pe_offset = struct.unpack_from("<I", raw, 0x3C)[0]
    if pe_offset + 26 > len(raw) or raw[pe_offset:pe_offset + 4] != b"PE\x00\x00":
        raise ValueError("Win64 smoke PE signature invalid")
    machine = struct.unpack_from("<H", raw, pe_offset + 4)[0]
    optional_magic = struct.unpack_from("<H", raw, pe_offset + 24)[0]
    return machine, optional_magic


def build_win64_smoke(work: Path) -> Path:
    source = work / "pocketpc-win64-smoke.c"
    output = work / "pocketpc-win64-smoke.exe"
    source.write_text(
        """#include <windows.h>

int main(void) {
    static const char message[] = "POCKETPC_WIN64_SMOKE_OK\\r\\n";
    DWORD written = 0;
    HANDLE out = GetStdHandle(STD_OUTPUT_HANDLE);
    if (out == INVALID_HANDLE_VALUE || out == NULL) return 10;
    if (!WriteFile(out, message, sizeof(message) - 1, &written, NULL)) return 11;
    return written == sizeof(message) - 1 ? 0 : 12;
}
""",
        encoding="utf-8",
    )
    run(
        [
            "x86_64-w64-mingw32-gcc",
            "-Os",
            "-s",
            "-static",
            "-Wl,--no-insert-timestamp",
            "-o",
            str(output),
            str(source),
        ],
        work,
        work / "win64-smoke-build.log",
    )
    machine, magic = pe_machine(output)
    if machine != 0x8664 or magic != 0x20B:
        raise SystemExit(
            "WIN64_SMOKE_PE_TARGET_MISMATCH "
            f"machine=0x{machine:04x} optional=0x{magic:04x}"
        )
    return output


def flatten_install_tree(source_root: Path, package_root: Path) -> list[dict[str, object]]:
    records: list[dict[str, object]] = []
    for source in sorted(source_root.rglob("*"), key=lambda p: p.as_posix()):
        relative = source.relative_to(source_root)
        if source.is_dir() and not source.is_symlink():
            continue
        resolved = source.resolve()
        try:
            resolved.relative_to(source_root.resolve())
        except ValueError as error:
            raise SystemExit(f"WINE_INSTALL_SYMLINK_ESCAPES:{relative}") from error
        if not resolved.is_file():
            raise SystemExit(f"WINE_INSTALL_UNSUPPORTED_ENTRY:{relative}")

        destination = package_root / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(resolved, destination)
        mode = resolved.stat().st_mode
        executable = bool(mode & 0o111)
        destination.chmod(0o755 if executable else 0o644)
        records.append(
            {
                "path": relative.as_posix(),
                "bytes": destination.stat().st_size,
                "sha256": sha256(destination),
                "executable": executable,
            }
        )
    return records


def deterministic_zip(zip_path: Path, package_root: Path, records: list[dict[str, object]]) -> None:
    with zipfile.ZipFile(
        zip_path,
        "w",
        compression=zipfile.ZIP_DEFLATED,
        compresslevel=9,
    ) as archive:
        ordered = [
            ("guest-tool-manifest.json", False),
            *[(str(item["path"]), bool(item["executable"])) for item in records],
        ]
        for relative, executable in ordered:
            source = package_root / relative
            info = zipfile.ZipInfo(relative, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = (0o100755 if executable else 0o100644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, source.read_bytes())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", type=Path, required=True)
    args = parser.parse_args()

    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    work = args.work.resolve()
    repo = ROOT.resolve()
    if work == repo or repo in work.parents:
        raise SystemExit("WINE_WORK_MUST_BE_OUTSIDE_REPOSITORY")
    work.mkdir(parents=True, exist_ok=False)

    source = work / "source"
    build = work / "build"
    destdir = work / "destdir"
    package_root = work / "guest-package"
    package_root.mkdir()

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
    actual = subprocess.check_output(
        ["git", "-C", str(source), "rev-parse", "HEAD"],
        text=True,
    ).strip()
    if actual != lock["commit"]:
        raise SystemExit("WINE_SOURCE_COMMIT_MISMATCH")

    copying = (source / "COPYING.LIB").read_text(encoding="utf-8", errors="replace")
    if "GNU LESSER GENERAL PUBLIC LICENSE" not in copying or "Version 2.1" not in copying:
        raise SystemExit("WINE_LICENSE_EVIDENCE_MISMATCH")

    build.mkdir()
    common_env = {
        "LC_ALL": "C",
        "LANG": "C",
        "SOURCE_DATE_EPOCH": "0",
    }
    configure = [
        str(source / "configure"),
        "--prefix=/opt/pocketpc/wine",
        "--enable-win64",
        "--disable-tests",
        "--with-mingw",
        "--without-x",
        "--without-wayland",
        "--without-alsa",
        "--without-pulse",
        "--without-dbus",
        "--without-cups",
        "--without-fontconfig",
        "--without-freetype",
        "--without-gphoto",
        "--without-gnutls",
        "--without-gstreamer",
        "--without-oss",
        "--without-pcap",
        "--without-sane",
        "--without-usb",
        "--without-v4l2",
        "--without-opencl",
        "--without-opengl",
        "--without-vulkan",
    ]
    run(configure, build, work / "configure.log", common_env)
    run(["make", "-j2"], build, work / "make.log", common_env)
    destdir.mkdir()
    run(
        ["make", "install", "DESTDIR=" + str(destdir)],
        build,
        work / "make-install.log",
        common_env,
    )

    installed_root = destdir / "opt/pocketpc/wine"
    wine = installed_root / "bin/wine"
    if not wine.is_file():
        raise SystemExit("WINE_ENTRYPOINT_MISSING")
    elf_class, elf_type, machine = elf_header(wine)
    if elf_class != 2 or machine != 62 or elf_type not in (2, 3):
        raise SystemExit(
            "WINE_ELF_TARGET_MISMATCH "
            f"class={elf_class} type={elf_type} machine={machine}"
        )

    records = flatten_install_tree(installed_root, package_root)
    smoke = build_win64_smoke(work)
    smoke_relative = Path("share/tests/pocketpc-win64-smoke.exe")
    smoke_destination = package_root / smoke_relative
    smoke_destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(smoke, smoke_destination)
    smoke_destination.chmod(0o644)
    records.append(
        {
            "path": smoke_relative.as_posix(),
            "bytes": smoke_destination.stat().st_size,
            "sha256": sha256(smoke_destination),
            "executable": False,
        }
    )
    records.sort(key=lambda item: str(item["path"]))

    guest_manifest = {
        "schemaVersion": 1,
        "id": "wine",
        "version": lock["version"],
        "architecture": "x86_64",
        "executionMode": "box64-x86_64",
        "guestRoot": "/opt/pocketpc/wine",
        "entrypoint": "bin/wine",
        "sourceCommit": actual,
        "license": lock["license"],
        "files": records,
    }
    manifest_path = package_root / "guest-tool-manifest.json"
    manifest_path.write_text(
        json.dumps(guest_manifest, indent=2) + "\n",
        encoding="utf-8",
    )

    zip_path = work / "guest-package.zip"
    deterministic_zip(zip_path, package_root, records)

    evidence = {
        "schemaVersion": 1,
        "status": "WINE_X86_64_HEADLESS_COMPILED_PACKAGE_NOT_GUEST_TESTED_NOT_APPROVED",
        "version": lock["version"],
        "sourceCommit": actual,
        "sourceLockSha256": sha256(LOCK_PATH),
        "entrypoint": {
            "path": "bin/wine",
            "sha256": sha256(package_root / "bin/wine"),
            "machine": machine,
        },
        "win64Smoke": {
            "path": smoke_relative.as_posix(),
            "sha256": sha256(smoke_destination),
            "expectedOutput": "POCKETPC_WIN64_SMOKE_OK",
        },
        "package": {
            "fileCount": len(records),
            "manifestSha256": sha256(manifest_path),
            "zipBytes": zip_path.stat().st_size,
            "zipSha256": sha256(zip_path),
        },
        "limitations": [
            "headless smoke configuration",
            "graphics disabled",
            "audio disabled",
            "TLS/gnutls disabled",
        ],
        "notExecuted": [
            "Wine under Box64",
            "wineboot prefix creation",
            "Win64 smoke executable",
            "DXVK/vkd3d",
            "Roblox",
        ],
    }
    (work / "wine-build-evidence.json").write_text(
        json.dumps(evidence, indent=2) + "\n",
        encoding="utf-8",
    )
    print("WINE_X86_64_HEADLESS_PACKAGE_READY_FOR_REVIEW_NOT_RUNTIME_TESTED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
