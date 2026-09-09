#!/usr/bin/env python3
"""Build only the PocketPC Wine USER driver from an already prepared Wine tree.

The source tree must be the exact pinned Wine 11.0 checkout with the
winepocketpc.drv overlay already applied. The script emits evidence for
configure/build success or failure, but never claims driver load or runtime
execution.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "third_party/wine/LOCK.json"

CONFIGURE_ARGS = (
    "--enable-win64",
    "--without-x",
    "--without-wayland",
    "--without-alsa",
    "--without-pulse",
    "--without-dbus",
    "--without-cups",
    "--without-fontconfig",
    "--without-freetype",
    "--without-gphoto",
    "--without-gstreamer",
    "--without-oss",
    "--without-pcap",
    "--without-sane",
    "--without-usb",
    "--without-v4l2",
    "--without-opencl",
    "--without-opengl",
    "--without-vulkan",
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def git_head(source: Path) -> str:
    return subprocess.check_output(
        ["git", "-C", str(source), "rev-parse", "HEAD"],
        text=True,
    ).strip()


def run_logged(
    argv: list[str],
    cwd: Path,
    log: Path,
) -> int:
    with log.open("w", encoding="utf-8") as output:
        result = subprocess.run(
            argv,
            cwd=cwd,
            stdout=output,
            stderr=subprocess.STDOUT,
            text=True,
            check=False,
        )
    return result.returncode


def elf_identity(path: Path) -> dict[str, int]:
    raw = path.read_bytes()[:20]
    if len(raw) < 20 or raw[:4] != b"\x7fELF":
        raise RuntimeError(
            "WINE_POCKETPC_UNIXLIB_NOT_ELF"
        )
    elf_class = raw[4]
    endian = raw[5]
    if endian != 1:
        raise RuntimeError(
            "WINE_POCKETPC_UNIXLIB_ENDIAN_INVALID"
        )
    elf_type, machine = struct.unpack_from(
        "<HH",
        raw,
        16,
    )
    if (
        elf_class != 2
        or machine != 62
        or elf_type not in (2, 3)
    ):
        raise RuntimeError(
            "WINE_POCKETPC_UNIXLIB_TARGET_INVALID:"
            f"class={elf_class}:type={elf_type}:machine={machine}"
        )
    return {
        "elfClass": elf_class,
        "elfType": elf_type,
        "machine": machine,
    }


def discover_build_targets(
    build: Path,
    makefile: Path,
) -> tuple[list[str], list[str]]:
    candidates = (
        "dlls/winepocketpc.drv",
        "dlls/winepocketpc.drv/winepocketpc.drv",
        "dlls/winepocketpc.drv/winepocketpc.so",
    )

    database = subprocess.run(
        [
            "make",
            "-qp",
            "-f",
            str(makefile),
        ],
        cwd=build,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        check=False,
    ).stdout

    discovered = {
        line.split(":", 1)[0].strip()
        for line in database.splitlines()
        if ":" in line
        and not line.startswith("\t")
        and not line.startswith("#")
        and "=" not in line.split(":", 1)[0]
    }

    present = [
        target
        for target in candidates
        if target in discovered
    ]

    aggregate = candidates[0]
    pe = candidates[1]
    unixlib = candidates[2]

    if aggregate in present:
        return [aggregate], present

    if pe in present and unixlib in present:
        return [pe, unixlib], present

    # Fall back to the generated Makefile text only for diagnostics
    # if make's database output is incomplete on a platform.
    text = makefile.read_text(
        encoding="utf-8",
        errors="replace",
    )
    direct = {
        line.split(":", 1)[0].strip()
        for line in text.splitlines()
        if ":" in line
        and not line.startswith("\t")
        and not line.startswith("#")
    }
    fallback = [
        target
        for target in candidates
        if target in direct
    ]

    if aggregate in fallback:
        return [aggregate], fallback
    if pe in fallback and unixlib in fallback:
        return [pe, unixlib], fallback

    return [], sorted(
        set(present) | set(fallback)
    )


def write_evidence(
    path: Path,
    data: dict[str, object],
) -> None:
    path.write_text(
        json.dumps(
            data,
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--wine-source",
        type=Path,
        required=True,
    )
    parser.add_argument(
        "--work",
        type=Path,
        required=True,
    )
    args = parser.parse_args()

    source = args.wine_source.resolve()
    work = args.work.resolve()
    repo = ROOT.resolve()

    if (
        work == repo
        or repo in work.parents
        or source == repo
        or repo in source.parents
    ):
        raise SystemExit(
            "WINE_DRIVER_BUILD_PATH_MUST_BE_OUTSIDE_REPOSITORY"
        )
    if work.exists():
        raise SystemExit(
            "WINE_DRIVER_BUILD_WORK_ALREADY_EXISTS"
        )
    work.mkdir(parents=True)

    evidence_path = (
        work /
        "wine-pocketpc-driver-build-evidence.json"
    )
    lock = json.loads(
        LOCK.read_text(encoding="utf-8")
    )

    base: dict[str, object] = {
        "schemaVersion": 1,
        "wineVersion": lock["version"],
        "wineCommit": lock["commit"],
        "driverName": "winepocketpc.drv",
        "unixLibrary": "winepocketpc.so",
        "protocolVersion": 3,
        "driverLoaded": False,
        "runtimeExecuted": False,
        "androidExecuted": False,
        "robloxExecuted": False,
    }

    if (
        not (source / ".git").exists()
        or git_head(source) != lock["commit"]
    ):
        base["status"] = "SOURCE_IDENTITY_FAILED"
        write_evidence(evidence_path, base)
        return 20

    driver_source = (
        source /
        "dlls/winepocketpc.drv"
    )
    if (
        not driver_source.is_dir()
        or not (
            driver_source /
            "Makefile.in"
        ).is_file()
    ):
        base["status"] = "DRIVER_OVERLAY_MISSING"
        write_evidence(evidence_path, base)
        return 21

    configure_ac = (
        source /
        "configure.ac"
    ).read_text(encoding="utf-8")
    configure_text = (
        source /
        "configure"
    ).read_text(encoding="utf-8")
    if (
        "WINE_CONFIG_MAKEFILE(dlls/winepocketpc.drv)"
        not in configure_ac
        or
        "wine_fn_config_makefile dlls/winepocketpc.drv enable_winepocketpc_drv"
        not in configure_text
    ):
        base["status"] = "DRIVER_CONFIGURE_REGISTRATION_MISSING"
        write_evidence(evidence_path, base)
        return 22

    build = work / "build"
    build.mkdir()

    configure_log = (
        work /
        "configure.log"
    )
    build_log = (
        work /
        "driver-build.log"
    )

    configure_argv = [
        str(source / "configure"),
        *CONFIGURE_ARGS,
    ]
    base["configure"] = configure_argv
    configure_rc = run_logged(
        configure_argv,
        build,
        configure_log,
    )
    base["configureExitCode"] = configure_rc
    if configure_rc != 0:
        base["status"] = "CONFIGURE_FAILED"
        base["logs"] = {
            "configure": str(configure_log),
        }
        write_evidence(evidence_path, base)
        print(
            "WINE_POCKETPC_DRIVER_CONFIGURE_FAILED"
        )
        return 30

    generated_makefile = (
        build /
        "Makefile"
    )
    if not generated_makefile.is_file():
        base["status"] = "GENERATED_MAKEFILE_MISSING"
        write_evidence(evidence_path, base)
        return 31

    build_targets, discovered_targets = (
        discover_build_targets(
            build,
            generated_makefile,
        )
    )
    base["discoveredBuildTargets"] = (
        discovered_targets
    )
    if not build_targets:
        base["status"] = "BUILD_TARGET_NOT_FOUND"
        base["targetCandidates"] = [
            "dlls/winepocketpc.drv",
            "dlls/winepocketpc.drv/winepocketpc.drv",
            "dlls/winepocketpc.drv/winepocketpc.so",
        ]
        write_evidence(evidence_path, base)
        print(
            "WINE_POCKETPC_DRIVER_BUILD_TARGET_NOT_FOUND"
        )
        return 32

    base["selectedBuildTargets"] = (
        build_targets
    )
    base["buildCommand"] = [
        "make",
        "-j2",
        *build_targets,
    ]

    build_rc = run_logged(
        [
            "make",
            "-j2",
            *build_targets,
        ],
        build,
        build_log,
    )
    base["buildExitCode"] = build_rc
    base["logs"] = {
        "configure": str(configure_log),
        "build": str(build_log),
    }
    if build_rc != 0:
        base["status"] = "BUILD_FAILED"
        write_evidence(evidence_path, base)
        print(
            "WINE_POCKETPC_DRIVER_BUILD_FAILED"
        )
        return 33

    output_dir = (
        build /
        "dlls/winepocketpc.drv"
    )
    pe = (
        output_dir /
        "winepocketpc.drv"
    )
    unixlib = (
        output_dir /
        "winepocketpc.so"
    )

    if not pe.is_file():
        base["status"] = "PE_DRIVER_ARTIFACT_MISSING"
        write_evidence(evidence_path, base)
        return 34
    if pe.read_bytes()[:2] != b"MZ":
        base["status"] = "PE_DRIVER_HEADER_INVALID"
        write_evidence(evidence_path, base)
        return 35
    if not unixlib.is_file():
        base["status"] = "UNIXLIB_ARTIFACT_MISSING"
        write_evidence(evidence_path, base)
        return 36

    try:
        unix_identity = elf_identity(
            unixlib
        )
    except Exception as error:
        base["status"] = "UNIXLIB_AUDIT_FAILED"
        base["auditError"] = str(error)
        write_evidence(evidence_path, base)
        return 37

    base["status"] = (
        "COMPILED_X86_64_NOT_LOADED_NOT_RUNTIME_TESTED"
    )
    base["artifacts"] = {
        "peDriver": {
            "path": str(pe),
            "bytes": pe.stat().st_size,
            "sha256": sha256(pe),
            "header": "MZ",
        },
        "unixLibrary": {
            "path": str(unixlib),
            "bytes": unixlib.stat().st_size,
            "sha256": sha256(unixlib),
            **unix_identity,
        },
    }
    base["notExecuted"] = [
        "winepocketpc.drv LoadLibrary",
        "Graphics=pocketpc driver selection",
        "HWND lifecycle through driver",
        "surface presentation",
        "input injection",
        "Box64 execution",
        "Android execution",
        "DXVK/Vulkan",
        "Roblox",
    ]

    write_evidence(
        evidence_path,
        base,
    )
    print(
        "WINE_POCKETPC_DRIVER_COMPILED_NOT_RUNTIME_TESTED"
    )
    print(
        "runtime_execution_evidence=false"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
