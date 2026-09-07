#!/usr/bin/env python3
"""Regression test for build-generated and local-only repository paths."""

from __future__ import annotations

import pathlib
import shutil
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
GITIGNORE = ROOT / ".gitignore"

GENERATED_PATHS = (
    ".gradle/9.6/fileHashes/fileHashes.bin",
    ".kotlin/sessions/kotlin-compiler.salive",
    ".idea/workspace.xml",
    ".vscode/settings.json",
    "local.properties",
    "build/reports/root.txt",
    "app/build/outputs/apk/debug/app-debug.apk",
    "app/.cxx/Debug/hash/arm64-v8a/build.ninja",
    "app/.externalNativeBuild/cmake/debug/arm64-v8a/build.ninja",
    "app/captures/screen.png",
    "local-build/alpha/physical-validation/device-evidence.json",
    "scripts/__pycache__/verifier.cpython-314.pyc",
    "module.iml",
    "keystore.properties",
    "release.jks",
    "java_pid123.hprof",
    ".DS_Store",
    "Thumbs.db",
)

SOURCE_PATHS = (
    ".github/workflows/android-ci.yml",
    "app/build.gradle.kts",
    "app/src/main/java/dev/pocketpc/core/MainActivity.kt",
    "app/src/main/cpp/runtime_host.cpp",
    "scripts/build-local-windows.ps1",
    "third_party/proot/LOCK.json",
    "toolchains/android-build-lock.json",
)


def check_ignored(repository: pathlib.Path, relative: str) -> bool:
    result = subprocess.run(
        [
            "git",
            "-C",
            str(repository),
            "check-ignore",
            "--quiet",
            "--no-index",
            "--",
            relative,
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode not in (0, 1):
        raise RuntimeError(
            f"git check-ignore failed for {relative}: {result.stderr.strip()}"
        )
    return result.returncode == 0


def materialize(repository: pathlib.Path, relative: str) -> None:
    target = repository / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(b"fixture")


def main() -> int:
    if shutil.which("git") is None:
        raise RuntimeError("git not found")
    if not GITIGNORE.is_file():
        raise RuntimeError(".gitignore not found")

    failures: list[str] = []
    with tempfile.TemporaryDirectory(prefix="pocketpc-gitignore-") as temp:
        repository = pathlib.Path(temp)
        subprocess.run(
            ["git", "init", "--quiet", str(repository)],
            check=True,
            capture_output=True,
            text=True,
        )
        shutil.copyfile(GITIGNORE, repository / ".gitignore")

        for relative in GENERATED_PATHS + SOURCE_PATHS:
            materialize(repository, relative)

        for relative in GENERATED_PATHS:
            if not check_ignored(repository, relative):
                failures.append(f"generated path is not ignored: {relative}")

        for relative in SOURCE_PATHS:
            if check_ignored(repository, relative):
                failures.append(f"source path is unexpectedly ignored: {relative}")

    if failures:
        print("GITIGNORE_POLICY_FAILED")
        for failure in failures:
            print(f"- {failure}")
        return 1

    print("GITIGNORE_POLICY_OK")
    print(f"generated_paths={len(GENERATED_PATHS)}")
    print(f"source_paths={len(SOURCE_PATHS)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
