#!/usr/bin/env python3
from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[1]

helper = (ROOT / "scripts/termux-ensure-aapt2.sh").read_text(encoding="utf-8")
gate = (ROOT / "scripts/termux-kotlin-unit-test.sh").read_text(encoding="utf-8")
wrapper = (ROOT / "PocketPC-Termux-Update-Test.sh").read_text(encoding="utf-8")
repo_check = (ROOT / "scripts/termux-repository-check.sh").read_text(encoding="utf-8")
lock = json.loads((ROOT / "toolchains/android-build-lock.json").read_text(encoding="utf-8"))

errors: list[str] = []

platform_package = lock["android"]["platformPackage"]
compile_sdk = str(lock["android"]["compileSdk"])

required_helper_fragments = (
    'read_lock android.platformPackage',
    'pkg install -y aapt',
    'termux-repository-check.sh --require-modern',
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

if 'bash scripts/termux-repository-check.sh --require-modern' not in wrapper:
    errors.append("Termux update wrapper does not fail early on legacy repositories")
else:
    repo_index = wrapper.index('bash scripts/termux-repository-check.sh --require-modern')
    preflight_index = wrapper.index('bash scripts/termux-on-device-preflight.sh')
    if repo_index > preflight_index:
        errors.append("Termux repository gate must run before device preflight")

for sentinel in (
    "TERMUX_REPOSITORY_LEGACY",
    "packages.termux.dev/apt/termux-main",
    "termux.net",
):
    if sentinel not in repo_check:
        errors.append(f"repository check missing sentinel: {sentinel}")

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
print("fallback=official_termux_packages_only")
print("sdk_downgrade=forbidden")
