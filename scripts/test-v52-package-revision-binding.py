#!/usr/bin/env python3
"""Static fail-closed lock for PocketPC APK <-> experimental Wine v52 revision binding.

This script does not build or execute Android, Wine, Vulkan, JNI, or Roblox.
It verifies only that source wiring requires a pinned PocketPC Git revision in
both the running APK and the experimental Wine capability sidecar, that the
manual builder/workflow cannot silently create revision-agnostic v52 metadata,
and that the experimental package builder cannot silently omit the Wine
entrypoint or the GDI window-smoke fixture needed by the first controlled
Windows-on-Android validation stage.
"""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILDER = ROOT / "scripts/build-wine-x86_64-v52-experimental.py"
RESOLVER = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeGraphicsGuestDeclarationResolver.kt"
GRADLE = ROOT / "app/build.gradle.kts"
EXPERIMENTAL_WORKFLOW = ROOT / ".github/workflows/wine-x86_64-v52-experimental.yml"
OFFICIAL_WORKFLOW = ROOT / ".github/workflows/wine-x86_64-build.yml"


def fail(message: str) -> None:
    raise SystemExit(f"FAIL v52 package revision binding: {message}")


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        fail(f"{label} missing {needle!r}")


def forbid(text: str, needle: str, label: str) -> None:
    if needle in text:
        fail(f"{label} contains forbidden {needle!r}")


def main() -> None:
    builder = BUILDER.read_text(encoding="utf-8")
    resolver = RESOLVER.read_text(encoding="utf-8")
    gradle = GRADLE.read_text(encoding="utf-8")
    experimental_workflow = EXPERIMENTAL_WORKFLOW.read_text(encoding="utf-8")
    official_workflow = OFFICIAL_WORKFLOW.read_text(encoding="utf-8")

    # The APK carries a revision only when it is a real 40-hex Git commit.
    require(gradle, 'providers.environmentVariable("GITHUB_SHA")', "APK revision source")
    require(gradle, 'providers.environmentVariable("POCKETPC_SOURCE_REVISION")', "APK local revision override")
    require(gradle, 'Regex("^[0-9a-fA-F]{40}$").matches(pocketPcSourceRevision)', "APK pinned revision gate")
    require(gradle, '"POCKETPC_SOURCE_REVISION"', "APK BuildConfig revision")
    require(gradle, '"POCKETPC_SOURCE_REVISION_PINNED"', "APK BuildConfig pinned flag")

    # The experimental package cannot be trusted without the same kind of pin.
    require(builder, 'os.environ.get("GITHUB_SHA")', "v52 package revision source")
    require(builder, 'os.environ.get("POCKETPC_SOURCE_REVISION")', "v52 package local revision override")
    require(builder, 'POCKETPC_COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")', "v52 package revision regex")
    require(builder, 'WINE_V52_POCKETPC_SOURCE_REVISION_NOT_PINNED', "v52 package unpinned blocker")
    require(builder, '"pocketPcSourceRevision": pocketpc_source_revision', "v52 sidecar revision")
    require(builder, '"pocketPcSourceRevision": pocketpc_source_revision,', "v52 evidence revision")

    # A revision-bound ZIP is still unusable for the first controlled GDI
    # device test if the Wine entrypoint or window fixture disappears. Lock the
    # exact guest paths and the fail-closed manifest/ZIP integrity verifier.
    require(builder, 'EXPECTED_GUEST_ROOT = "/opt/pocketpc/wine"', "v52 Wine guest root")
    require(builder, 'WINE_ENTRYPOINT_RELATIVE = Path("bin/wine")', "v52 Wine entrypoint")
    require(
        builder,
        'WINDOW_SMOKE_RELATIVE = Path("share/tests/pocketpc-window-smoke.exe")',
        "v52 GDI window fixture",
    )
    require(builder, "def verify_required_guest_payload(work: Path)", "v52 required payload verifier")
    require(builder, 'WINE_V52_GUEST_ROOT_MISMATCH', "v52 guest-root blocker")
    require(builder, 'WINE_V52_ENTRYPOINT_MISMATCH', "v52 entrypoint blocker")
    require(builder, 'WINE_V52_REQUIRED_MANIFEST_RECORD_MISSING', "v52 manifest record blocker")
    require(builder, 'WINE_V52_REQUIRED_FILE_DIGEST_MISMATCH', "v52 package digest blocker")
    require(builder, 'WINE_V52_REQUIRED_ZIP_FILE_MISSING', "v52 ZIP presence blocker")
    require(builder, 'WINE_V52_REQUIRED_ZIP_DIGEST_MISMATCH', "v52 ZIP digest blocker")
    require(builder, '"verifiedRequiredGuestPayload": required_payload', "v52 payload evidence")
    require(builder, '"runtimeExecuted": False', "v52 runtime fail-closed evidence")
    require(builder, 'V52_REQUIRED_GUEST_PAYLOAD_VERIFIED=1', "v52 payload verification marker")

    # Runtime promotes metadata only when the sidecar hash is still valid and
    # its PocketPC revision exactly matches the pinned revision in this APK.
    require(resolver, 'import dev.pocketpc.core.BuildConfig', "runtime APK build identity")
    require(resolver, 'BuildConfig.POCKETPC_SOURCE_REVISION', "runtime expected revision")
    require(resolver, 'BuildConfig.POCKETPC_SOURCE_REVISION_PINNED', "runtime pinned revision flag")
    require(resolver, '!expectedPocketPcSourceRevisionPinned', "runtime unpinned blocker")
    require(resolver, 'json.optString("pocketPcSourceRevision", "")', "runtime sidecar revision")
    require(resolver, 'declaredPocketPcRevision != normalizedPocketPcRevision', "runtime exact revision match")
    require(resolver, 'sha256(file) != listed.sha256', "runtime sidecar hash gate")
    require(resolver, 'verifiedArtifactMetadata = true', "runtime promotion after gates")
    require(resolver, '!capabilities.add(value)', "duplicate capability blocker")

    # The manual workflow must independently attest that package/evidence carry
    # the commit that GitHub actually checked out for this run.
    require(experimental_workflow, 'expected_pocketpc_revision = os.environ["GITHUB_SHA"].strip().lower()', "workflow expected revision")
    require(experimental_workflow, 'capability_json["pocketPcSourceRevision"] == expected_pocketpc_revision', "workflow sidecar revision check")
    require(experimental_workflow, 'capability["pocketPcSourceRevision"] == expected_pocketpc_revision', "workflow evidence revision check")

    # Official Wine remains v51 and never opts into the experimental builder.
    require(official_workflow, "python3 scripts/build-wine-x86_64-v51.py", "official v51 builder")
    forbid(official_workflow, "build-wine-x86_64-v52-experimental.py", "official Wine workflow")
    forbid(official_workflow, "POCKETPC_VULKAN_CONTINUOUS_PRESENT_V52=1", "official Wine workflow")

    print("PASS static v52 package revision + core guest payload binding")
    print("CLASSIFICATION=IMPLEMENTED_SOURCE_NOT_EXECUTED")
    print("APK_REVISION_PIN_REQUIRED=1")
    print("WINE_V52_REVISION_PIN_REQUIRED=1")
    print("APK_WINE_V52_EXACT_REVISION_MATCH_REQUIRED=1")
    print("WINE_V52_CORE_GUEST_PAYLOAD_REQUIRED=1")
    print("WINE_V52_WINDOW_SMOKE_FIXTURE_REQUIRED=1")
    print("OFFICIAL_WINE_BUILD=v51")
    print("RUNTIME=NOT_EXECUTED")
    print("PHYSICAL=NOT_EXECUTED")
    print("ROBLOX=NOT_EXECUTED")


if __name__ == "__main__":
    main()
