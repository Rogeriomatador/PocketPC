#!/usr/bin/env python3
"""Static policy guard for winepocketpc.drv load-evidence instrumentation."""

from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
DRIVER = ROOT / "third_party/wine/pocketpc-driver/pocketpcdrv_main.c"
VERIFIER = ROOT / "scripts/verify-wine-pocketpc-driver-load-evidence.py"
TEST = ROOT / "scripts/test-wine-pocketpc-driver-load-evidence.py"
WORKFLOW = ROOT / ".github/workflows/wine-pocketpc-driver-build.yml"


def require(failures: list[str], label: str, text: str, values: tuple[str, ...]) -> None:
    for value in values:
        if value not in text:
            failures.append(f"{label} missing: {value}")


def main() -> int:
    failures: list[str] = []
    for path in (DRIVER, VERIFIER, TEST, WORKFLOW):
        if not path.is_file():
            failures.append(f"missing required file: {path.relative_to(ROOT)}")
    if failures:
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    driver = DRIVER.read_text(encoding="utf-8")
    verifier = VERIFIER.read_text(encoding="utf-8")
    test = TEST.read_text(encoding="utf-8")
    workflow = WORKFLOW.read_text(encoding="utf-8")

    require(
        failures,
        "driver markers",
        driver,
        (
            "POCKETPC_DRIVER_LOAD stage=unix_init_begin protocol=%u",
            "POCKETPC_DRIVER_LOAD stage=bridge_connected protocol=%u",
            "POCKETPC_DRIVER_LOAD stage=bridge_connect_failed protocol=%u",
            "POCKETPC_DRIVER_LOAD stage=namespace_failed protocol=%u",
            "POCKETPC_DRIVER_LOAD stage=user_driver_registered protocol=%u",
            "__wine_set_user_driver",
        ),
    )
    require(
        failures,
        "load verifier",
        verifier,
        (
            "EXPECTED_PROTOCOL = 4",
            "unix_init_begin|bridge_connected|user_driver_registered|bridge_connect_failed|namespace_failed",
            '"graphics_registry_selection_proved": False',
            '"surface_presented": False',
            '"physical_validation": False',
            "bridge failure marker present in registered evidence",
            "namespace failure marker present in registered evidence",
        ),
    )
    require(
        failures,
        "load verifier tests",
        test,
        (
            "generic_log_false_positive=rejected",
            "bridge_failure_not_promoted=rejected",
            "wrong_protocol=rejected",
            "contradictory_failure=rejected",
            "graphics_registry_selection_proved=false",
            "physical_validation=false",
        ),
    )
    require(
        failures,
        "driver workflow",
        workflow,
        (
            "scripts/test-wine-pocketpc-driver-load-evidence.py",
            "scripts/test-wine-pocketpc-driver-load-policy.py",
        ),
    )

    if failures:
        print("WINE_POCKETPC_DRIVER_LOAD_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    print("WINE_POCKETPC_DRIVER_LOAD_POLICY_OK")
    print("stable_load_markers=guarded")
    print("false_positive_log_text=rejected_by_test")
    print("graphics_registry_selection_proved=false")
    print("physical_validation=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
