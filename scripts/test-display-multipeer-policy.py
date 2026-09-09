#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
PROTOCOL = ROOT / "third_party/wine/POCKETPC_DISPLAY_BRIDGE_PROTOCOL.json"
AUDIT = ROOT / "third_party/wine/ANDROID_DRIVER_REUSE.json"
WINDOW_MAP_H = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_wine_window_map.h"
WINDOW_MAP_C = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_wine_window_map.c"
WINDOW_MAP_SMOKE = ROOT / "third_party/wine/pocketpc-display-bridge/window_map_smoke.c"
DRIVER_MAIN = ROOT / "third_party/wine/pocketpc-driver/pocketpcdrv_main.c"
DESKTOP = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeDesktopBridge.kt"
EXECUTION = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeDisplayExecutionController.kt"
DESKTOP_TEST = ROOT / "app/src/test/java/dev/pocketpc/core/runtime/RuntimeDesktopBridgeTest.kt"
RUNTIME_APP = ROOT / "app/src/main/java/dev/pocketpc/core/ui/RuntimeApp.kt"


def require(
    failures: list[str],
    label: str,
    text: str,
    markers: tuple[str, ...],
) -> None:
    for marker in markers:
        if marker not in text:
            failures.append(
                f"{label} missing: {marker}"
            )


def main() -> int:
    failures: list[str] = []

    try:
        protocol = json.loads(
            PROTOCOL.read_text(encoding="utf-8"),
        )
        audit = json.loads(
            AUDIT.read_text(encoding="utf-8"),
        )
    except Exception as error:
        print(
            "DISPLAY_MULTIPEER_POLICY_FAILED",
            file=sys.stderr,
        )
        print(f"- json: {error}", file=sys.stderr)
        return 1

    expected_true = (
        "wineProcessWindowNamespaceImplemented",
        "multiPeerDesktopRoutingImplemented",
        "multiPeerDisplayExecutionImplemented",
    )
    expected_false = (
        "wineProcessWindowNamespaceSoftwareTestExecuted",
        "multiPeerDesktopRoutingSoftwareTestExecuted",
        "multiPeerDisplayExecutionSoftwareTestExecuted",
    )

    for label, document in (
        ("protocol", protocol),
        ("audit", audit),
    ):
        gates = document.get("gates") or {}
        for key in expected_true:
            if gates.get(key) is not True:
                failures.append(
                    f"{label} expected true gate: {key}"
                )
        for key in expected_false:
            if gates.get(key) is not False:
                failures.append(
                    f"{label} expected false gate: {key}"
                )

    texts = {
        "window map header":
            WINDOW_MAP_H.read_text(encoding="utf-8"),
        "window map source":
            WINDOW_MAP_C.read_text(encoding="utf-8"),
        "window map smoke":
            WINDOW_MAP_SMOKE.read_text(encoding="utf-8"),
        "driver main":
            DRIVER_MAIN.read_text(encoding="utf-8"),
        "desktop bridge":
            DESKTOP.read_text(encoding="utf-8"),
        "display execution":
            EXECUTION.read_text(encoding="utf-8"),
        "desktop tests":
            DESKTOP_TEST.read_text(encoding="utf-8"),
        "runtime app":
            RUNTIME_APP.read_text(encoding="utf-8"),
    }

    require(
        failures,
        "window map header",
        texts["window map header"],
        (
            "uint32_t window_namespace;",
            "uint32_t next_local_window_id;",
            "pdb_wine_window_map_set_namespace",
        ),
    )
    require(
        failures,
        "window map source",
        texts["window map source"],
        (
            "map->window_namespace",
            "map->next_local_window_id",
            "<< 32",
            "UINT32_MAX",
            "pdb_wine_window_count(map) != 0u",
        ),
    )
    require(
        failures,
        "driver process namespace",
        texts["driver main"],
        (
            "#include <unistd.h>",
            "(uint32_t)getpid()",
            "0x7fffffffu",
            "pdb_wine_window_map_set_namespace",
            "process_namespace",
        ),
    )
    require(
        failures,
        "window map namespace smoke",
        texts["window map smoke"],
        (
            "0x1234u",
            "pdb_wine_window_map_set_namespace",
            "((uint64_t)0x1234u << 32)",
            "namespace=pid32",
        ),
    )
    require(
        failures,
        "desktop multi-binding routing",
        texts["desktop bridge"],
        (
            "BindingState",
            "windowOwners",
            "ownerForLocked",
            "publishMergedLocked",
            "RUNTIME_DESKTOP_WINDOW_OWNER_COLLISION",
            "RUNTIME_DESKTOP_WINDOW_DUPLICATE_GLOBAL",
        ),
    )
    require(
        failures,
        "multi-peer execution",
        texts["display execution"],
        (
            "authenticatedPeerCount",
            "MAX_AUTHENTICATED_PEERS",
            "startPeer(",
            "acceptLoop",
            "host.acceptAuthenticated",
            "desktopMultiplexer",
            "DISPLAY_BRIDGE_PEER_LIMIT_REACHED",
        ),
    )
    require(
        failures,
        "desktop ownership tests",
        texts["desktop tests"],
        (
            "concurrentBindingsPublishAndRouteCommandsByWindowOwner",
            "releasingOneBindingLeavesOtherPeerWindowsAlive",
            "crossBindingWindowCollisionFailsClosedWithoutStealingOwner",
            "pointerAndKeyboardRouteToOwningPeer",
            "topmostWindowsStayAboveNormalWindowsAcrossPeers",
        ),
    )
    require(
        failures,
        "runtime peer evidence",
        texts["runtime app"],
        (
            "Peers Wine autenticados:",
            "authenticatedPeerCount",
        ),
    )

    multi = protocol.get("multiProcessDisplay") or {}
    if (
        multi.get("peerLimit") != 16
        or
        multi.get("status")
        != "IMPLEMENTED_STATICALLY_NOT_EXECUTED"
    ):
        failures.append(
            "multiProcessDisplay contract changed"
        )

    if failures:
        print(
            "DISPLAY_MULTIPEER_POLICY_FAILED",
            file=sys.stderr,
        )
        for failure in failures:
            print(
                "- " + failure,
                file=sys.stderr,
            )
        return 1

    print("DISPLAY_MULTIPEER_POLICY_OK")
    print("window_identity=pid31_shift32_plus_local32")
    print("authenticated_peer_limit=16")
    print("software_test_execution=false")
    print("physical_execution=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
