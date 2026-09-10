#!/usr/bin/env python3
"""Verify stable winepocketpc.drv load/registration markers from a Wine debug log."""

from __future__ import annotations
import argparse, json, re
from pathlib import Path

EXPECTED_PROTOCOL = 4
MARKER = re.compile(
    r"POCKETPC_DRIVER_LOAD\s+stage="
    r"(unix_init_begin|bridge_connected|user_driver_registered|bridge_connect_failed|namespace_failed)"
    r"\s+protocol=(\d+)"
)

def load_json(path: Path) -> dict[str, object]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise RuntimeError(f"JSON is not an object: {path}")
    return value

def validate_static_precondition(
    artifact_set_path: Path | None,
    package_evidence_path: Path | None,
) -> str:
    if (artifact_set_path is None) == (package_evidence_path is None):
        raise RuntimeError("exactly one static precondition evidence source is required")
    if artifact_set_path is not None:
        evidence = load_json(artifact_set_path)
        if evidence.get("status") != "pass":
            raise RuntimeError("artifact-set evidence status is not pass")
        if evidence.get("scope") != "static_artifact_set_coherence":
            raise RuntimeError("artifact-set evidence scope is invalid")
        claims = evidence.get("claims")
        if not isinstance(claims, dict):
            raise RuntimeError("artifact-set claims object is missing")
        if claims.get("same_build_evidence_set") is not True:
            raise RuntimeError("artifact-set coherence was not proven")
        if claims.get("static_binary_identity") is not True:
            raise RuntimeError("static binary identity was not proven")
        for key in ("driver_loaded", "runtime_executed", "android_executed", "physical_validation"):
            if claims.get(key) is not False:
                raise RuntimeError(
                    f"artifact-set claim {key} must remain false before load verification"
                )
        return "driver_artifact_set"

    evidence = load_json(package_evidence_path)
    if evidence.get("status") != "pass":
        raise RuntimeError("full Wine package evidence status is not pass")
    if evidence.get("scope") != "full_wine_package_driver_static_identity":
        raise RuntimeError("full Wine package evidence scope is invalid")
    if evidence.get("protocolVersion") != EXPECTED_PROTOCOL:
        raise RuntimeError("full Wine package protocol is not v4")
    claims = evidence.get("claims")
    if not isinstance(claims, dict):
        raise RuntimeError("full Wine package claims object is missing")
    if claims.get("full_wine_build_static_identity") is not True:
        raise RuntimeError("full Wine package static identity was not proven")
    if claims.get("driver_pair_hashes_match_build_evidence") is not True:
        raise RuntimeError("full Wine package driver hashes were not proven")
    for key in (
        "driver_loaded",
        "graphics_registry_selection_proved",
        "surface_presented",
        "input_round_trip",
        "android_executed",
        "dxvk_vulkan_executed",
        "roblox_executed",
        "physical_validation",
    ):
        if claims.get(key) is not False:
            raise RuntimeError(
                f"full Wine package claim {key} must remain false before load verification"
            )
    return "full_wine_package"

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", type=Path, required=True)
    static_group = parser.add_mutually_exclusive_group(required=True)
    static_group.add_argument("--artifact-set", type=Path)
    static_group.add_argument("--package-evidence", type=Path)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument(
        "--expect",
        choices=("entry", "bridge", "registered"),
        default="registered",
    )
    args = parser.parse_args()

    try:
        if not args.log.is_file():
            raise RuntimeError(f"missing Wine driver log: {args.log}")
        precondition = validate_static_precondition(
            args.artifact_set,
            args.package_evidence,
        )

        text = args.log.read_text(encoding="utf-8", errors="replace")
        events: list[tuple[int, str, int]] = [
            (match.start(), match.group(1), int(match.group(2)))
            for match in MARKER.finditer(text)
        ]
        if not events:
            raise RuntimeError("no PocketPC driver load markers found")
        if any(protocol != EXPECTED_PROTOCOL for _, _, protocol in events):
            raise RuntimeError("PocketPC driver marker protocol mismatch")

        first: dict[str, int] = {}
        for position, stage, _protocol in events:
            first.setdefault(stage, position)

        entry = first.get("unix_init_begin")
        bridge = first.get("bridge_connected")
        registered = first.get("user_driver_registered")
        bridge_failed = first.get("bridge_connect_failed")
        namespace_failed = first.get("namespace_failed")

        if entry is None:
            raise RuntimeError("driver Unix init entry marker was not observed")
        if args.expect in ("bridge", "registered"):
            if bridge is None:
                raise RuntimeError("display bridge connection marker was not observed")
            if bridge <= entry:
                raise RuntimeError("display bridge marker occurred before driver init entry")
        if args.expect == "registered":
            if registered is None:
                raise RuntimeError("USER driver registration marker was not observed")
            if bridge is None or registered <= bridge:
                raise RuntimeError("USER driver registration marker order is invalid")
            if bridge_failed is not None:
                raise RuntimeError("bridge failure marker present in registered evidence")
            if namespace_failed is not None:
                raise RuntimeError("namespace failure marker present in registered evidence")

        verified_level = (
            "registered"
            if registered is not None
            and bridge is not None
            and registered > bridge > entry
            and bridge_failed is None
            and namespace_failed is None
            else "bridge"
            if bridge is not None and bridge > entry and bridge_failed is None
            else "entry"
        )

        output = {
            "schema": 2,
            "status": "pass",
            "scope": "wine_driver_load_markers",
            "staticPrecondition": precondition,
            "expectedProtocol": EXPECTED_PROTOCOL,
            "requestedLevel": args.expect,
            "verifiedLevel": verified_level,
            "events": [stage for _, stage, _ in events],
            "claims": {
                "driver_unix_init_entry_observed": entry is not None,
                "display_bridge_connected_observed": bridge is not None,
                "wine_user_driver_registered_observed": registered is not None,
                "graphics_registry_selection_proved": False,
                "surface_presented": False,
                "input_round_trip": False,
                "android_executed": False,
                "dxvk_vulkan_executed": False,
                "roblox_executed": False,
                "physical_validation": False,
            },
        }
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        args.evidence.write_text(
            json.dumps(output, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    except (OSError, ValueError, RuntimeError) as exc:
        print(f"WINE_POCKETPC_DRIVER_LOAD_VERIFY_FAIL={exc}")
        return 1

    print("WINE_POCKETPC_DRIVER_LOAD_VERIFY_OK")
    print(f"static_precondition={precondition}")
    print(f"verified_level={verified_level}")
    print("graphics_registry_selection_proved=false")
    print("surface_presented=false")
    print("runtime_game_execution_evidence=false")
    print("physical_validation=false")
    print(f"evidence={args.evidence}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
