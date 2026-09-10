#!/usr/bin/env python3
from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[1]

helper = (ROOT / "scripts/termux-ensure-aapt2.sh").read_text(encoding="utf-8")
gate = (ROOT / "scripts/termux-kotlin-unit-test.sh").read_text(encoding="utf-8")
wrapper = (ROOT / "PocketPC-Termux-Update-Test.sh").read_text(encoding="utf-8")
repo_check = (ROOT / "scripts/termux-repository-check.sh").read_text(encoding="utf-8")
repo_repair = (ROOT / "scripts/termux-repair-repository.sh").read_text(encoding="utf-8")
keyring_repair = (ROOT / "scripts/termux-repair-keyring.sh").read_text(encoding="utf-8")
variant_detect = (ROOT / "scripts/termux-detect-variant.sh").read_text(encoding="utf-8")
local_builder = (ROOT / "scripts/termux-build-modern-aapt2.sh").read_text(encoding="utf-8")
local_builder_alias = (ROOT / "scripts/termux-build-aapt2-from-source.sh").read_text(encoding="utf-8")
preflight = (ROOT / "scripts/termux-on-device-preflight.sh").read_text(encoding="utf-8")
lock = json.loads((ROOT / "toolchains/android-build-lock.json").read_text(encoding="utf-8"))

errors: list[str] = []

platform_package = lock["android"]["platformPackage"]
compile_sdk = str(lock["android"]["compileSdk"])

required_helper_fragments = (
    'read_lock android.platformPackage',
    'pkg install -y aapt',
    'termux-repository-check.sh --require-compatible',
    'TERMUX_AAPT2_PLATFORM_INCOMPATIBLE_AFTER_OFFICIAL_UPDATE',
    'Do not lower compileSdk',
)
for fragment in required_helper_fragments:
    if fragment not in helper:
        errors.append(f"missing helper policy fragment: {fragment}")

for forbidden in (
    "Commit451",
    "android-arm-build-tools",
    "pkg install -y aapt2",
    "curl -fsSL",
    "wget ",
    "platforms/android-36",
    "platforms;android-36",
):
    if forbidden in helper:
        errors.append(f"forbidden AAPT2 fallback/downgrade fragment: {forbidden}")

if 'bash scripts/termux-ensure-aapt2.sh' not in gate:
    errors.append("Kotlin/unit-test gate does not invoke the AAPT2 compatibility gate")
else:
    ensure_index = gate.index('bash scripts/termux-ensure-aapt2.sh')
    compile_index = gate.index(':app:compileDebugKotlin')
    if ensure_index > compile_index:
        errors.append("AAPT2 compatibility gate must run before Kotlin compile gate")

if 'bash scripts/termux-repository-check.sh --require-compatible' not in wrapper:
    errors.append("Termux update wrapper does not validate repository compatibility")
else:
    repo_index = wrapper.index('bash scripts/termux-repository-check.sh --require-compatible')
    preflight_index = wrapper.index('bash scripts/termux-on-device-preflight.sh')
    if repo_index > preflight_index:
        errors.append("Termux repository gate must run before device preflight")

for sentinel in (
    "TERMUX_REPOSITORY_GOOGLE_PLAY_OK",
    "TERMUX_REPOSITORY_VARIANT_MIXED",
    "TERMUX_REPOSITORY_VARIANT_MISMATCH",
    "TERMUX_REPOSITORY_LEGACY",
    "packages.termux.dev/apt/termux-main",
    "termux-detect-variant.sh",
):
    if sentinel not in repo_check:
        errors.append(f"repository check missing sentinel: {sentinel}")

if "termux[.]net" not in repo_check and "termux.net" not in repo_check:
    errors.append("repository check does not recognize termux.net")

if "TERMUX_GOOGLE_PLAY_LOCAL_AAPT2_BUILD_FAIL" not in helper:
    errors.append("AAPT2 helper does not classify failed pinned local recovery on Google Play")
if "TERMUX_LOCAL_AAPT2_PLATFORM_PASS_AFTER_BUILD" not in helper:
    errors.append("AAPT2 helper does not accept the freshly built pinned local AAPT2")
if "bash scripts/termux-build-modern-aapt2.sh" not in helper:
    errors.append("AAPT2 helper does not automatically invoke the pinned local builder on Google Play")

if "TERMUX_KOTLIN_COMPILE_PASS_UNIT_TEST_BLOCKED_AAPT2" not in gate:
    errors.append("Kotlin gate does not preserve compile PASS when Google Play AAPT2 blocks unit tests")


for sentinel in (
    'path.suffix == ".sources"',
    "TERMUX_VARIANT",
    "googleplay",
    "MIXED_GOOGLE_PLAY_AND_CLASSIC",
):
    if sentinel not in repo_check and sentinel not in variant_detect:
        errors.append(f"Termux variant/repository support missing sentinel: {sentinel}")

for sentinel in (
    "TERMUX_VARIANT",
    "googleplay",
    "termux.net",
    "packages.termux.dev",
    "--require-compatible",
):
    if sentinel not in repo_repair:
        errors.append(f"variant-aware repository repair missing sentinel: {sentinel}")

if "TERMUX_KEYRING_REPAIR_BLOCKED_GOOGLE_PLAY" not in keyring_repair:
    errors.append("classic Termux keyring repair is not blocked on Google Play variant")

for sentinel in (
    'SOURCE_REPO="https://github.com/termux/android-build-tools.git"',
    'SOURCE_TAG="16.0.0.4"',
    'SOURCE_SHA="c4edf8539a34a8600538e6642c1ecb170452a79e"',
    'INSTALL_ROOT="${HOME}/.local/pocketpc/android-build-tools/${SOURCE_TAG}"',
    'INSTALL_BIN="$INSTALL_ROOT/bin/aapt2"',
    'TERMUX_LOCAL_AAPT2_BUILD_PASS',
    'termux-repository-check.sh --require-compatible',
    'sha256sum',
):
    if sentinel not in local_builder:
        errors.append(f"local AAPT2 builder missing pinned sentinel: {sentinel}")

if "termux-build-modern-aapt2.sh" not in helper:
    errors.append("AAPT2 helper does not surface the pinned local builder")

if "android-build-tools/16.0.0.4/bin/aapt2" not in gate:
    errors.append("Kotlin gate does not select the pinned local AAPT2 path")
if "android-build-tools/16.0.0.4/bin/aapt2" not in preflight:
    errors.append("Termux preflight does not inspect the pinned local AAPT2 path")
if 'exec bash "$ROOT/scripts/termux-build-modern-aapt2.sh" "$@"' not in local_builder_alias:
    errors.append("legacy AAPT2 builder entrypoint does not delegate to canonical builder")
for dependency in (
    "libc++",
    "libzopfli",
    "zlib",
    "protobuf-dev",
    "protobuf_generate_PROTOC_EXE",
    "linux-headers",
    "ndk-sysroot",
    "HEADER_PACKAGE",
    "TERMUX_AAPT2_BUILD_DEPENDENCIES_UNAVAILABLE",
):
    if dependency not in local_builder:
        errors.append(f"local AAPT2 builder missing official dependency/config sentinel: {dependency}")

for label, text_blob in (
    ("AAPT2 helper", helper),
    ("local AAPT2 builder", local_builder),
    ("Kotlin gate", gate),
    ("Termux preflight", preflight),
):
    if "awk '/Candidate:/ {print $2; exit}'" in text_blob:
        errors.append(f"{label} contains SIGPIPE-prone apt-cache/awk early exit")
    if "find " in text_blob and "|\n        head -1" in text_blob:
        errors.append(f"{label} contains SIGPIPE-prone find/head pipeline")

for insecure in (
    "trusted=yes",
    "--allow-unauthenticated",
    "--allow-insecure-repositories",
    "Acquire::AllowInsecureRepositories",
):
    if insecure in helper or insecure in repo_repair or insecure in keyring_repair or insecure in local_builder:
        errors.append(f"insecure Termux recovery option is forbidden: {insecure}")

if platform_package != f"platforms;android-{compile_sdk}.0":
    errors.append(
        f"locked platform mismatch: compileSdk={compile_sdk} platformPackage={platform_package}"
    )

if errors:
    for error in errors:
        print(f"TERMUX_AAPT2_POLICY_FAIL: {error}", file=sys.stderr)
    raise SystemExit(1)

print("TERMUX_AAPT2_POLICY_OK")
print(f"compile_sdk={compile_sdk}")
print(f"platform_package={platform_package}")
print("fallback=variant_aware_official_package_or_pinned_local_build")
print("sdk_downgrade=forbidden")
