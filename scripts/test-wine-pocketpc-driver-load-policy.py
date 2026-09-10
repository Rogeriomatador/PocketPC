#!/usr/bin/env python3
"""Static policy guard for winepocketpc.drv load-evidence instrumentation."""

from __future__ import annotations
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
DRIVER = ROOT / "third_party/wine/pocketpc-driver/pocketpcdrv_main.c"
VERIFIER = ROOT / "scripts/verify-wine-pocketpc-driver-load-evidence.py"
TEST = ROOT / "scripts/test-wine-pocketpc-driver-load-evidence.py"
BROKER_TEST = ROOT / "scripts/test-wine-pocketpc-driver-load-broker.py"
SMOKE = ROOT / "scripts/run-wine-pocketpc-driver-load-smoke.py"
WORKFLOW = ROOT / ".github/workflows/wine-pocketpc-driver-build.yml"
FULL_WORKFLOW = ROOT / ".github/workflows/wine-x86_64-build.yml"


def require(failures: list[str], label: str, text: str, values: tuple[str, ...]) -> None:
    for value in values:
        if value not in text:
            failures.append(f"{label} missing: {value}")


def main() -> int:
    failures: list[str] = []
    required_files = (
        DRIVER,
        VERIFIER,
        TEST,
        BROKER_TEST,
        SMOKE,
        WORKFLOW,
        FULL_WORKFLOW,
    )
    for path in required_files:
        if not path.is_file():
            failures.append(f"missing required file: {path.relative_to(ROOT)}")
    if failures:
        print("WINE_POCKETPC_DRIVER_LOAD_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    driver = DRIVER.read_text(encoding="utf-8")
    verifier = VERIFIER.read_text(encoding="utf-8")
    test = TEST.read_text(encoding="utf-8")
    broker_test = BROKER_TEST.read_text(encoding="utf-8")
    smoke = SMOKE.read_text(encoding="utf-8")
    workflow = WORKFLOW.read_text(encoding="utf-8")
    full_workflow = FULL_WORKFLOW.read_text(encoding="utf-8")

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
            "full_wine_package_driver_static_identity",
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
            "artifact_set_precondition=accepted",
            "full_wine_package_precondition=accepted",
            "generic_log_false_positive=rejected",
            "bridge_failure_not_promoted=rejected",
            "wrong_protocol=rejected",
            "contradictory_failure=rejected",
            "false_package_preclaim=rejected",
            "graphics_registry_selection_proved=false",
            "physical_validation=false",
        ),
    )
    require(
        failures,
        "registration smoke",
        smoke,
        (
            '"Graphics","/t","REG_SZ","/d","pocketpc","/f"',
            "REGISTRY_RE",
            "RegistrationBroker",
            "POCKETPC_DISPLAY_PROTOCOL",
            "POCKETPC_DISPLAY_RUNTIME_SHA256",
            "POCKETPC_DISPLAY_HOST_CAPS",
            "POCKETPC_DRIVER_LOAD stage=user_driver_registered protocol=4",
            "verify-wine-pocketpc-driver-load-evidence.py",
            '"surface_presented":False',
            '"input_round_trip":False',
            '"android_executed":False',
            '"box64_executed":False',
            '"physical_validation":False',
        ),
    )
    require(
        failures,
        "registration broker test",
        broker_test,
        (
            "valid_protocol_v4_hello=accepted",
            "hello_ack_caps=23",
            "wrong_token=rejected",
            "wine_execution=false",
            "physical_validation=false",
        ),
    )
    require(
        failures,
        "isolated driver workflow",
        workflow,
        (
            "scripts/test-wine-pocketpc-driver-load-evidence.py",
            "scripts/test-wine-pocketpc-driver-load-policy.py",
        ),
    )
    require(
        failures,
        "full Wine workflow",
        full_workflow,
        (
            "scripts/run-wine-pocketpc-driver-load-smoke.py",
            "scripts/test-wine-pocketpc-driver-load-evidence.py",
            "scripts/test-wine-pocketpc-driver-load-broker.py",
            "scripts/test-wine-pocketpc-driver-load-policy.py",
            "Prove host Wine loads and registers winepocketpc.drv",
            "--package-evidence",
            "--smoke-exe",
            "pocketpc-window-smoke.exe",
            "pocketpc-wine-driver-load-smoke/*.json",
            "pocketpc-wine-driver-load-smoke/*.log",
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
    print("host_wine_registration_smoke=guarded")
    print("graphics_registry_configuration=guarded")
    print("failure_logs=preserved")
    print("android_execution=false")
    print("physical_validation=false")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
