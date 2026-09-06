#!/usr/bin/env python3
"""Verify PocketPC's pinned PRoot source metadata and source archives.

This script downloads source archives only. It never builds, installs or copies
third-party binaries into the Android application.
"""

from __future__ import annotations

import hashlib
import json
import pathlib
import re
import sys
import tempfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
LOCK_PATH = ROOT / "third_party" / "proot" / "LOCK.json"
RAW_RECIPE = "https://raw.githubusercontent.com/{repo}/{commit}/{path}"
HEX64 = re.compile(r"^[0-9a-f]{64}$")


def fetch_text(url: str) -> str:
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "PocketPC-source-audit/1"},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode("utf-8")


def download_and_hash(url: str, destination: pathlib.Path) -> str:
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "PocketPC-source-audit/1"},
    )
    digest = hashlib.sha256()
    with urllib.request.urlopen(request, timeout=90) as response, destination.open("wb") as output:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
            output.write(chunk)
    return digest.hexdigest()


def main() -> int:
    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    if lock.get("schemaVersion") != 1:
        raise SystemExit("unsupported LOCK schemaVersion")

    authority = lock["recipeAuthority"]
    recipe_repo = authority["repository"]
    recipe_commit = authority["commit"]
    failures: list[str] = []

    with tempfile.TemporaryDirectory(prefix="pocketpc-proot-audit-") as temp:
        temp_dir = pathlib.Path(temp)

        for component in lock["components"]:
            component_id = component["id"]
            expected = component["sourceSha256"].lower()

            if not HEX64.fullmatch(expected):
                failures.append(f"{component_id}: invalid SHA-256 in lock")
                continue

            recipe_url = RAW_RECIPE.format(
                repo=recipe_repo,
                commit=recipe_commit,
                path=component["recipePath"],
            )
            try:
                recipe = fetch_text(recipe_url)
            except Exception as error:
                failures.append(f"{component_id}: recipe fetch failed: {error}")
                continue

            for assertion in component.get("recipeAssertions", []):
                if assertion not in recipe:
                    failures.append(
                        f"{component_id}: recipe assertion missing: {assertion!r}"
                    )

            destination = temp_dir / f"{component_id}.source"
            try:
                actual = download_and_hash(component["sourceUrl"], destination)
            except Exception as error:
                failures.append(f"{component_id}: source download failed: {error}")
                continue

            if actual != expected:
                failures.append(
                    f"{component_id}: SHA-256 mismatch expected={expected} actual={actual}"
                )
            else:
                print(
                    f"OK {component_id} {component['version']} "
                    f"sha256={actual}"
                )

    if failures:
        print("\nSOURCE AUDIT FAILED", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("\nSOURCE AUDIT PASSED")
    print(f"recipe authority: {recipe_repo}@{recipe_commit}")
    print("No third-party binaries were built or bundled.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
