#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
AUDIT = ROOT / "third_party/wine/ANDROID_DRIVER_REUSE.json"
PROTOCOL = ROOT / "third_party/wine/POCKETPC_DISPLAY_BRIDGE_PROTOCOL.json"
TEMPLATE = ROOT / "third_party/wine/pocketpc-driver"
PREPARER = ROOT / "scripts/prepare-wine-pocketpc-driver.py"

FILES = {
    "makefile": TEMPLATE / "Makefile.in",
    "dllmain": TEMPLATE / "dllmain.c",
    "input": TEMPLATE / "input.c",
    "unixlib": TEMPLATE / "unixlib.h",
    "dll_header": TEMPLATE / "pocketpcdrv_dll.h",
    "driver_header": TEMPLATE / "pocketpcdrv.h",
    "main": TEMPLATE / "pocketpcdrv_main.c",
    "surface": TEMPLATE / "surface.c",
    "window": TEMPLATE / "window.c",
}


def require(
    failures: list[str],
    label: str,
    text: str,
    sentinels: tuple[str, ...],
) -> None:
    for sentinel in sentinels:
        if sentinel not in text:
            failures.append(
                f"{label} missing: {sentinel}"
            )


def main() -> int:
    failures: list[str] = []

    try:
        audit = json.loads(
            AUDIT.read_text(encoding="utf-8")
        )
        protocol = json.loads(
            PROTOCOL.read_text(encoding="utf-8")
        )
    except Exception as error:
        print(
            "WINE_POCKETPC_DRIVER_POLICY_FAILED",
            file=sys.stderr,
        )
        print(f"- json: {error}", file=sys.stderr)
        return 1

    if (
        audit.get("wineVersion") != "11.0"
        or audit.get("wineSourceCommit")
        != "db11d0fe6a169c457e23d007e20404643d067aa8"
    ):
        failures.append(
            "Wine source identity changed"
        )

    gates = audit.get("gates") or {}
    expected_true = (
        "protocolImplemented",
        "hostBrokerImplemented",
        "windowIdentityMapImplemented",
        "windowLifecycleAdapterImplemented",
        "guestDriverTemplateImplemented",
        "guestDriverOverlayPreparerImplemented",
        "surfaceCallbackTemplateImplemented",
        "inputInjectionTemplateImplemented",
        "surfaceRequestProtocolImplemented",
        "surfaceWriterAttestationImplemented",
        "surfaceTransactionDemuxImplemented",
        "hostSurfaceRegistryImplemented",
        "singleBufferOwnershipAckImplemented",
        "surfaceQualifiedFrameAckImplemented",
        "processEventsMaskPreservationImplemented",
        "frameFailureFailClosedImplemented",
        "windowLifecyclePostSendFailClosedImplemented",
        "bridgeReadySynchronizationImplemented",
        "frameAckControlPlaneDrainImplemented",
        "surfaceRegistryPublishFailClosedImplemented",
        "authTimeoutScopedToHandshakeImplemented",
        "guestCapabilityBaselineNegotiationImplemented",
        "controlledWindowsAttemptPlanningImplemented",
        "pcTargetMaterializationImplemented",
        "runtimeEvidenceIdentityCoherenceImplemented",
        "continuousAppDesktopBridgeImplemented",
        "dxvkLaunchOverrideImplemented",
        "winePocketPcWindowProbeBrokerImplemented",
        "winePocketPcWindowEvidenceImplemented",
        "wineGraphicsDriverSelectionImplemented",
        "wineDriverAbiLockImplemented",
        "wineGraphicsConfigurationIsolationImplemented",
        "wineProcessWindowNamespaceImplemented",
        "multiPeerDesktopRoutingImplemented",
        "multiPeerDisplayExecutionImplemented",
        "wineLauncherChildDisplayHandoffImplemented",
    )
    expected_false = (
        "protocolV4NativeIntegrationExecuted",
        "surfaceQualifiedFrameAckSoftwareTestExecuted",
        "processEventsMaskPreservationSoftwareTestExecuted",
        "frameFailureFailClosedSoftwareTestExecuted",
        "windowLifecyclePostSendFailClosedSoftwareTestExecuted",
        "bridgeReadySynchronizationSoftwareTestExecuted",
        "frameAckControlPlaneDrainSoftwareTestExecuted",
        "surfaceRegistryPublishFailClosedSoftwareTestExecuted",
        "authTimeoutScopedToHandshakeSoftwareTestExecuted",
        "guestCapabilityBaselineNegotiationSoftwareTestExecuted",
        "controlledWindowsAttemptPlanningSoftwareTestExecuted",
        "pcTargetMaterializationSoftwareTestExecuted",
        "runtimeEvidenceIdentityCoherenceSoftwareTestExecuted",
        "continuousAppDesktopBridgeSoftwareTestExecuted",
        "dxvkLaunchOverrideSoftwareTestExecuted",
        "winePocketPcWindowProbeBrokerSoftwareTestExecuted",
        "winePocketPcWindowEvidenceSoftwareTestExecuted",
        "wineGraphicsDriverSelectionSoftwareTestExecuted",
        "wineDriverAbiLockSoftwareTestExecuted",
        "wineGraphicsConfigurationIsolationSoftwareTestExecuted",
        "wineProcessWindowNamespaceSoftwareTestExecuted",
        "multiPeerDesktopRoutingSoftwareTestExecuted",
        "multiPeerDisplayExecutionSoftwareTestExecuted",
        "wineLauncherChildDisplayHandoffSoftwareTestExecuted",
        "surfaceWriterNativeSoftwareTestExecuted",
        "surfaceVisibilityCrossProcessSoftwareTestExecuted",
        "doubleBufferingImplemented",
        "windowLifecycleAdapterSoftwareTestExecuted",
        "guestDriverBuilt",
        "guestDriverLoaded",
        "guestDriverImplemented",
        "surfacePresented",
        "pointerRoundTrip",
        "keyboardRoundTrip",
        "robloxWindowPresented",
    )
    for key in expected_true:
        if gates.get(key) is not True:
            failures.append(
                f"expected true gate: {key}"
            )
    for key in expected_false:
        if gates.get(key) is not False:
            failures.append(
                f"expected false gate: {key}"
            )

    if (
        (protocol.get("wire") or {})
            .get("version") != 4
    ):
        failures.append(
            "display bridge protocol is not v4"
        )

    texts: dict[str, str] = {}
    for label, path in FILES.items():
        if not path.is_file():
            failures.append(
                f"missing driver template file: {path.relative_to(ROOT)}"
            )
            continue
        texts[label] = path.read_text(
            encoding="utf-8"
        )

    require(
        failures,
        "Makefile",
        texts.get("makefile", ""),
        (
            "MODULE = winepocketpc.drv",
            "UNIXLIB = winepocketpc.so",
            "-lwin32u",
            "surface.c",
            "pocketpc_display_bridge.c",
            "pocketpc_surface_writer.c",
            "pocketpc_wine_window_map.c",
            "pocketpc_wine_window_bridge.c",
        ),
    )
    require(
        failures,
        "DLL entry",
        texts.get("dllmain", ""),
        (
            "__wine_init_unix_call()",
            "POCKETPCDRV_UNIX_CALL",
            "DLL_PROCESS_ATTACH",
        ),
    )
    require(
        failures,
        "Unix driver init",
        texts.get("main", ""),
        (
            "pdb_connect_from_environment",
            "pdb_wine_window_bridge_init",
            "pdb_wine_window_map_set_namespace",
            "process_namespace",
            "0x7fffffffu",
            "__wine_set_user_driver",
            "WINE_GDI_DRIVER_VERSION",
            "POCKETPC_BridgeReady",
            "pocketpc_connection.fd >= 0",
            ".pCreateWindow",
            ".pDestroyWindow",
            ".pProcessEvents",
            ".pCreateWindowSurface",
            ".pWindowPosChanging",
            ".pWindowPosChanged",
        ),
    )
    bridge_source = (
        ROOT /
        "third_party/wine/pocketpc-display-bridge/pocketpc_display_bridge.c"
    ).read_text(encoding="utf-8")
    require(
        failures,
        "Bridge timeout and validation",
        bridge_source,
        (
            "SO_RCVTIMEO",
            "SO_SNDTIMEO",
            "PDB_SOCKET_TIMEOUT_SECONDS",
            "PDB_POINTER_BUTTON_ALLOWED",
            "PDB_REQUIRED_CAPABILITIES_MISSING",
            "PDB_GUEST_IMPLEMENTED",
            "PDB_GUEST_REQUIRED",
            "pdb_peek_message_type",
            "pdb_receive_host_event",
        ),
    )
    require(
        failures,
        "Input pump",
        texts.get("input", ""),
        (
            "pdb_connection_has_input",
            "pdb_receive_host_event",
            "NtUserSendHardwareInput",
            "PDB_POINTER_ACTION_DOWN",
            "PDB_POINTER_ACTION_UP",
            "PDB_POINTER_ACTION_SCROLL",
            "KEYEVENTF_KEYUP",
            "PDB_KEY_ACTION_UP",
            "PDB_KEY_ACTION_REPEAT",
            "pdb_peek_message_type",
            "POCKETPC_DispatchHostEvent",
            "POCKETPC_MAX_EVENTS_PER_PUMP",
            "POCKETPC_HOST_EVENT_QUEUE_LIMIT",
            "POCKETPC_QueueHostEventLocked",
            "POCKETPC_DequeueHostEventLocked",
            "POCKETPC_DequeueHostEventForMaskLocked",
            "host_event_matches_mask",
            "POCKETPC_FailBridgeLocked",
            "PDB_HOST_EVENT_MASK_DEFERRED",
            "PDB_UNEXPECTED_HOST_EVENT",
            "PDB_MSG_FRAME_PRESENTED",
            "PDB_FRAME_ACK_REJECTED_OR_UNMATCHED",
            "FRAME_PRESENTED is a control-plane ownership ACK",
            "must drain for every Wine queue mask",
            "POCKETPC_BridgeReady()",
        ),
    )
    require(
        failures,
        "Wine surface callback",
        texts.get("surface", ""),
        (
            "POCKETPC_CreateWindowSurface",
            "window_surface_create",
            "pdb_send_surface_request",
            "pdb_receive_host_event",
            "pdb_surface_writer_open",
            "pdb_surface_writer_copy_bgra",
            "pdb_surface_writer_commit",
            "frame_in_flight",
            "in_flight_frame_id",
            "begin_surface_frame",
            "cancel_surface_frame",
            "POCKETPC_HandleFramePresented",
            "coalescing frame while ACK pending",
            "Wine dirty bounds retained",
            "Wine 11 win32u offsets the dirty rectangle",
            "max(dirty->left, 0)",
            "max(dirty->top, 0)",
            "already-attested surface",
            "surface->writer.surface.width * 4",
            "POCKETPC_FailBridgeLocked",
            "PDB_SURFACE_IDENTITY_MISMATCH",
            "PDB_SURFACE_RESPONSE_MISSING",
            "PDB_FRAME_READY_SEND_FAILED",
            "PDB_FRAME_ACK_REJECTED_DURING_SURFACE",
            "PDB_FRAME_SLOT_IDENTITY_MISMATCH",
            "PDB_SURFACE_REGISTRY_PUBLISH_FAILED",
            "POCKETPC_BridgeReady()",
            "POCKETPC_QueueHostEventLocked",
            "PDB_MSG_FRAME_PRESENTED",
            "PDB_MSG_SURFACE_AVAILABLE",
            "PDB_MSG_WINDOW_COMMAND",
            "PDB_HOST_EVENT_QUEUE_FULL",
            "pdb_surface_writer_close",
        ),
    )
    surface_text = (
        texts.get("surface", "")
    )
    if "dispatch_deferred_input" in surface_text:
        failures.append(
            "surface callback must not inject queued input directly"
        )
    if "pdb_receive_frame_presented" in surface_text:
        failures.append(
            "surface flush must not synchronously wait for FRAME_PRESENTED"
        )
    if "wait_frame_presented_locked" in surface_text:
        failures.append(
            "surface flush regressed to synchronous FRAME_PRESENTED wait"
        )
    if "FRAME_READY published" not in surface_text:
        failures.append(
            "surface flush must publish frame asynchronously"
        )

    pending_start = surface_text.find(
        "!begin_surface_frame("
    )
    pending_end = surface_text.find(
        "pdb_surface_writer_copy_bgra",
        pending_start,
    )
    if (
        pending_start < 0
        or pending_end <= pending_start
    ):
        failures.append(
            "surface ACK ownership block is missing"
        )
    else:
        pending_block = surface_text[
            pending_start:pending_end
        ]
        if "return FALSE;" not in pending_block:
            failures.append(
                "pending ACK must retain Wine dirty bounds by returning FALSE"
            )
        if "return TRUE;" in pending_block:
            failures.append(
                "pending ACK must not clear Wine dirty bounds"
            )

    for stale_dirty_transform in (
        "max(dirty->left, rect->left)",
        "max(dirty->top, rect->top)",
        "min(dirty->right, rect->right)",
        "min(dirty->bottom, rect->bottom)",
    ):
        if stale_dirty_transform in surface_text:
            failures.append(
                "surface dirty rect regressed to window-coordinate transform: "
                + stale_dirty_transform
            )

    require(
        failures,
        "Window callbacks",
        texts.get("window", ""),
        (
            "NtUserGetAncestor",
            "NtUserGetWindowRelative",
            "NtUserIsWindowVisible",
            "PDB_ZORDER_NO_CHANGE",
            "PDB_ZORDER_TOPMOST",
            "PDB_ZORDER_AFTER_WINDOW",
            "pdb_wine_window_bridge_create",
            "pdb_wine_window_bridge_geometry",
            "pdb_wine_window_bridge_destroy",
            "fail_closed_if_connection_invalid_locked",
            "pocketpc_connection.fd < 0",
            "POCKETPC_FailBridgeLocked",
            "POCKETPC_BridgeReady()",
            "pthread_mutex_lock",
            "pthread_mutex_unlock",
        ),
    )

    window_bridge_source = (
        ROOT /
        "third_party/wine/pocketpc-display-bridge/pocketpc_wine_window_bridge.c"
    ).read_text(encoding="utf-8")
    require(
        failures,
        "Window lifecycle post-send fail-closed",
        window_bridge_source,
        (
            "PDB_WINE_WINDOW_CREATE_STATE_FAILED",
            "PDB_WINE_WINDOW_GEOMETRY_STATE_FAILED",
            "PDB_WINE_WINDOW_DESTROY_STATE_FAILED",
            "PDB_WINE_WINDOW_UNREGISTER_FAILED_AFTER_SEND",
            "pdb_close(bridge->connection)",
        ),
    )
    if window_bridge_source.count(
        "pdb_close(bridge->connection)"
    ) < 4:
        failures.append(
            "window lifecycle post-send failures must close the bridge"
        )

    if not PREPARER.is_file():
        failures.append(
            "missing Wine PocketPC driver overlay preparer"
        )
    else:
        preparer = PREPARER.read_text(
            encoding="utf-8"
        )
        if (
            preparer.count(
                "if name in UNIX_ONLY_C_FILES:"
            ) < 2
        ):
            failures.append(
                "overlay does not mark bridge C sources Unix-only"
            )
        require(
            failures,
            "Overlay preparer",
            preparer,
            (
                "WINE_SOURCE_COMMIT_MISMATCH",
                "WINE_POCKETPC_DRIVER_DESTINATION_ALREADY_EXISTS",
                "WINE_CONFIG_MAKEFILE(dlls/winepocketpc.drv)",
                "wine_fn_config_makefile dlls/winepocketpc.drv",
                '"value": "pocketpc"',
                '"resolvedLibrary": "winepocketpc.drv"',
                '"surfaceCallbackImplemented": True',
                '"inputInjectionImplemented": True',
                '"pProcessEvents"',
                '"pCreateWindowSurface"',
                "UNIX_MAKEDEP_PREAMBLE",
                "#pragma makedep unix",
                '"bridgeSourcesMarkedUnixOnly": True',
                '"runtime_execution_evidence=false"',
            ),
        )

    plan = audit.get("pocketPcPlan") or {}
    if (
        plan.get("driverName") != "winepocketpc.drv"
        or plan.get("graphicsSelection")
        != "HKCU\\Software\\Wine\\Drivers\\Graphics=pocketpc"
        or plan.get("protocolVersion") != 4
    ):
        failures.append(
            "PocketPC Wine driver selection contract changed"
        )

    if failures:
        print(
            "WINE_POCKETPC_DRIVER_POLICY_FAILED",
            file=sys.stderr,
        )
        for failure in failures:
            print(
                "- " + failure,
                file=sys.stderr,
            )
        return 1

    print("WINE_POCKETPC_DRIVER_POLICY_OK")
    print("driver=winepocketpc.drv")
    print("protocol_version=4")
    print("driver_build_evidence=false")
    print("runtime_execution_evidence=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
