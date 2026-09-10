#!/usr/bin/env python3
"""Synthetic tests for fail-closed winepocketpc.drv load evidence."""

from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "scripts/verify-wine-pocketpc-driver-load-evidence.py"


def run(work: Path, expect: str = "registered") -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            str(VERIFIER),
            "--log",
            str(work / "wine.log"),
            "--artifact-set",
            str(work / "artifact-set.json"),
            "--evidence",
            str(work / "load.json"),
            "--expect",
            expect,
        ],
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        check=False,
    )


def write_artifact_set(work: Path) -> None:
    value = {
        "schema": 1,
        "status": "pass",
        "scope": "static_artifact_set_coherence",
        "claims": {
            "same_build_evidence_set": True,
            "static_binary_identity": True,
            "driver_loaded": False,
            "runtime_executed": False,
            "android_executed": False,
            "physical_validation": False,
        },
    }
    (work / "artifact-set.json").write_text(
        json.dumps(value),
        encoding="utf-8",
    )


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="pocketpc-driver-load-test-") as temp:
        work = Path(temp)
        write_artifact_set(work)

        (work / "wine.log").write_text(
            "0001:trace:pocketpcdrv: POCKETPC_DRIVER_LOAD stage=unix_init_begin protocol=4 pid=41\n"
            "0001:trace:pocketpcdrv: POCKETPC_DRIVER_LOAD stage=bridge_connected protocol=4 capabilities=255\n"
            "0001:trace:pocketpcdrv: POCKETPC_DRIVER_LOAD stage=user_driver_registered protocol=4 pid_namespace=41\n",
            encoding="utf-8",
        )
        good = run(work)
        if good.returncode != 0 or "verified_level=registered" not in good.stdout:
            raise AssertionError("valid registered sequence was rejected:\n" + good.stdout)

        evidence = json.loads((work / "load.json").read_text(encoding="utf-8"))
        claims = evidence["claims"]
        assert claims["driver_unix_init_entry_observed"] is True
        assert claims["display_bridge_connected_observed"] is True
        assert claims["wine_user_driver_registered_observed"] is True
        assert claims["graphics_registry_selection_proved"] is False
        assert claims["surface_presented"] is False
        assert claims["physical_validation"] is False

        (work / "wine.log").write_text(
            "wine loaded something called winepocketpc.drv successfully\n",
            encoding="utf-8",
        )
        generic = run(work)
        if generic.returncode == 0 or "no PocketPC driver load markers found" not in generic.stdout:
            raise AssertionError("generic log text produced a false positive:\n" + generic.stdout)

        (work / "wine.log").write_text(
            "POCKETPC_DRIVER_LOAD stage=unix_init_begin protocol=4 pid=41\n"
            "POCKETPC_DRIVER_LOAD stage=bridge_connect_failed protocol=4 error=refused\n",
            encoding="utf-8",
        )
        bridge_fail = run(work)
        if bridge_fail.returncode == 0 or "display bridge connection marker was not observed" not in bridge_fail.stdout:
            raise AssertionError("bridge failure was accepted as registered:\n" + bridge_fail.stdout)
        entry_only = run(work, "entry")
        if entry_only.returncode != 0 or "verified_level=entry" not in entry_only.stdout:
            raise AssertionError("entry-level evidence was not preserved:\n" + entry_only.stdout)

        (work / "wine.log").write_text(
            "POCKETPC_DRIVER_LOAD stage=unix_init_begin protocol=3 pid=41\n"
            "POCKETPC_DRIVER_LOAD stage=bridge_connected protocol=3 capabilities=255\n"
            "POCKETPC_DRIVER_LOAD stage=user_driver_registered protocol=3 pid_namespace=41\n",
            encoding="utf-8",
        )
        wrong_protocol = run(work)
        if wrong_protocol.returncode == 0 or "protocol mismatch" not in wrong_protocol.stdout:
            raise AssertionError("wrong protocol markers were accepted:\n" + wrong_protocol.stdout)

        (work / "wine.log").write_text(
            "POCKETPC_DRIVER_LOAD stage=unix_init_begin protocol=4 pid=41\n"
            "POCKETPC_DRIVER_LOAD stage=bridge_connected protocol=4 capabilities=255\n"
            "POCKETPC_DRIVER_LOAD stage=namespace_failed protocol=4 pid=41 namespace=41\n"
            "POCKETPC_DRIVER_LOAD stage=user_driver_registered protocol=4 pid_namespace=41\n",
            encoding="utf-8",
        )
        contradictory = run(work)
        if contradictory.returncode == 0 or "namespace failure marker present" not in contradictory.stdout:
            raise AssertionError("contradictory failure+registered evidence was accepted:\n" + contradictory.stdout)

    print("WINE_POCKETPC_DRIVER_LOAD_EVIDENCE_TEST_OK")
    print("registered_sequence=accepted")
    print("generic_log_false_positive=rejected")
    print("bridge_failure_not_promoted=rejected")
    print("wrong_protocol=rejected")
    print("contradictory_failure=rejected")
    print("graphics_registry_selection_proved=false")
    print("physical_validation=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
