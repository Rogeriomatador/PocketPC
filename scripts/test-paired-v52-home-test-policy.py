#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/publish-home-test-v52-paired.yml"
NORMAL_OTA = ROOT / ".github/workflows/publish-home-test-update.yml"
FEED = ROOT / "scripts/prepare-update-feed.py"
CATALOG = ROOT / "app/src/main/java/dev/pocketpc/core/update/PocketPcExperimentalRuntimeCatalog.kt"
INSTALLER = ROOT / "app/src/main/java/dev/pocketpc/core/update/PocketPcExperimentalRuntimeInstaller.kt"


def require(source: str, needle: str, label: str) -> None:
    if needle not in source:
        raise SystemExit(f"PAIRED_V52_POLICY_MISSING:{label}:{needle}")


def forbid(source: str, needle: str, label: str) -> None:
    if needle in source:
        raise SystemExit(f"PAIRED_V52_POLICY_FORBIDDEN:{label}:{needle}")


def main() -> int:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    normal_ota = NORMAL_OTA.read_text(encoding="utf-8")
    feed = FEED.read_text(encoding="utf-8")
    catalog = CATALOG.read_text(encoding="utf-8")
    installer = INSTALLER.read_text(encoding="utf-8")

    for needle, label in (
        ("PocketPC Paired v52 Home Test", "paired-workflow-name"),
        ("workflow_dispatch:", "manual-dispatch"),
        ("build-wine-x86_64-v52-experimental.py", "v52-wine-builder"),
        ("build-v52-continuous-present-smoke.py", "deterministic-smoke-builder"),
        ("POCKETPC_SOURCE_REVISION: ${{ github.sha }}", "apk-source-revision"),
        ("pocketPcSourceRevision", "runtime-source-revision-check"),
        ("wineVulkanAbi", "abi-check"),
        ("pocketpc.vulkan.continuous-present.v52", "capability-check"),
        ("runtimeExecuted", "runtime-fail-closed"),
        ("physicalVisibleFrame", "physical-fail-closed"),
        ("robloxExecuted", "roblox-fail-closed"),
        ("--experimental-runtime-zip", "paired-feed-runtime"),
        ("--experimental-runtime-url", "paired-feed-url"),
        ("--prerelease", "public-prerelease-only"),
    ):
        require(workflow, needle, label)

    for needle, label in (
        ("--experimental-runtime-zip", "feed-runtime-input"),
        ("guest-tool-manifest.json", "feed-manifest-verification"),
        ("runtime-graphics-capabilities.json", "feed-capability-sidecar"),
        ("experimentalRuntime", "feed-additive-runtime-offer"),
        ("pocketPcSourceRevision", "feed-revision-binding"),
    ):
        require(feed, needle, label)

    for needle, label in (
        ("POCKETPC_SOURCE_REVISION_PINNED", "catalog-pinned-apk-revision"),
        ("EXPERIMENTAL_RUNTIME_REVISION_MISMATCH", "catalog-revision-mismatch"),
        ("wineVulkanAbi", "catalog-abi52"),
        ("pocketpc.vulkan.continuous-present.v52", "catalog-exact-capability"),
        ("https://", "catalog-https"),
    ):
        require(catalog, needle, label)

    for needle, label in (
        ("MessageDigest.getInstance(\"SHA-256\")", "installer-sha256"),
        ("EXPERIMENTAL_RUNTIME_DOWNLOAD_SIZE_MISMATCH", "installer-size-verification"),
        ("EXPERIMENTAL_RUNTIME_DOWNLOAD_SHA256_MISMATCH", "installer-hash-verification"),
        ("packages.stageZip", "installer-package-verifier-path"),
        ("installer.install", "installer-verified-install-path"),
        ("EXPERIMENTAL_RUNTIME_REDIRECT_DOWNGRADE", "installer-https-redirect-gate"),
    ):
        require(installer, needle, label)

    # Normal push OTA remains APK-only. Experimental Wine is not silently
    # rebuilt/published on every development commit.
    forbid(
        normal_ota,
        "build-wine-x86_64-v52-experimental.py",
        "normal-ota-must-not-build-v52",
    )
    forbid(
        normal_ota,
        "--experimental-runtime-zip",
        "normal-ota-must-remain-apk-only",
    )

    # Publication source must not claim runtime/physical/Roblox validation.
    forbid(workflow, "RUNTIME_EXECUTED=1", "runtime-proof-not-earned")
    forbid(workflow, "PHYSICAL_VISIBLE_FRAME=1", "physical-proof-not-earned")
    forbid(workflow, "ROBLOX_EXECUTED=1", "roblox-proof-not-earned")

    print("POCKETPC_PAIRED_V52_HOME_TEST_POLICY_OK")
    print("PAIRED_BUILD_PATH_IMPLEMENTED=1")
    print("NORMAL_OTA_REMAINS_APK_ONLY=1")
    print("RUNTIME_EXECUTED=0")
    print("PHYSICAL_VISIBLE_FRAME=0")
    print("ROBLOX_EXECUTED=0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
