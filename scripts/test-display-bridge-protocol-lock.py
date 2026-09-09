#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "third_party/wine/POCKETPC_DISPLAY_BRIDGE_PROTOCOL.json"
KOTLIN = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeDisplayBridgeProtocol.kt"
PAYLOADS = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeDisplayBridgePayloads.kt"
FRAMEBUFFER = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeDisplaySharedFramebuffer.kt"
STATE_MACHINE = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeDisplayBridgeStateMachine.kt"
PROBE_CONTROLLER = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeDisplayBridgeProbeController.kt"
SURFACE_REGISTRY = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeDisplaySurfaceRegistry.kt"
FRAME_READER = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeDisplayFrameReader.kt"
HOST_PROCESSOR = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeDisplayHostProcessor.kt"
COMPOSITOR_MODEL = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeDisplayCompositorModel.kt"
SESSION_CONTROLLER = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeDisplaySessionController.kt"
BRIDGE_HOST = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeDisplayBridgeHost.kt"
FRAME_PREVIEW = ROOT / "app/src/main/java/dev/pocketpc/core/ui/RuntimeDisplayFramePreview.kt"
RUNTIME_APP = ROOT / "app/src/main/java/dev/pocketpc/core/ui/RuntimeApp.kt"
BOX64 = ROOT / "scripts/build-box64-aarch64.py"
HEADER = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_display_bridge.h"
SOURCE = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_display_bridge.c"
SMOKE = ROOT / "third_party/wine/pocketpc-display-bridge/display_bridge_smoke.c"
WINDOW_MAP_HEADER = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_wine_window_map.h"
WINDOW_MAP_SOURCE = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_wine_window_map.c"
WINDOW_BRIDGE_HEADER = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_wine_window_bridge.h"
WINDOW_BRIDGE_SOURCE = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_wine_window_bridge.c"
SURFACE_WRITER_HEADER = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_surface_writer.h"
SURFACE_WRITER_SOURCE = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_surface_writer.c"
VISIBILITY_SMOKE = ROOT / "third_party/wine/pocketpc-display-bridge/surface_writer_visibility_smoke.c"
NATIVE_INTEGRATION = ROOT / "scripts/test-display-bridge-native-integration.py"


def require_sentinels(
    failures: list[str],
    label: str,
    text: str,
    sentinels: tuple[str, ...],
) -> None:
    for sentinel in sentinels:
        if sentinel not in text:
            failures.append(f"{label} missing: {sentinel}")


def main() -> int:
    failures: list[str] = []

    try:
        lock = json.loads(
            LOCK.read_text(encoding="utf-8"),
        )
    except Exception as error:
        print(
            "DISPLAY_BRIDGE_PROTOCOL_LOCK_FAILED\n"
            f"- lock: {error}",
            file=sys.stderr,
        )
        return 1

    wire = lock.get("wire") or {}
    hello = lock.get("hello") or {}
    types = lock.get("messageTypes") or {}
    caps = lock.get("capabilities") or {}
    direction = lock.get("direction") or {}
    layouts = lock.get("payloadLayouts") or {}
    shared = lock.get("sharedFramebuffer") or {}
    z_order = lock.get("zOrder") or {}
    input_semantics =
        lock.get("inputSemantics") or {}

    checks = (
        (wire.get("magicHex"), "0x31424450", "wire magic"),
        (wire.get("version"), 3, "version"),
        (wire.get("headerBytes"), 20, "header"),
        (
            wire.get("maxPayloadBytes"),
            1024 * 1024,
            "max payload",
        ),
        (hello.get("tokenBytes"), 32, "token"),
        (
            hello.get("runtimeIdentityAsciiBytes"),
            64,
            "runtime identity",
        ),
        (hello.get("payloadBytes"), 100, "hello"),
        (hello.get("ackPayloadBytes"), 4, "ack"),
    )
    for actual, expected, label in checks:
        if actual != expected:
            failures.append(f"{label} changed")

    expected_types = {
        "HELLO": 1,
        "HELLO_ACK": 2,
        "WINDOW_CREATE": 10,
        "WINDOW_GEOMETRY": 11,
        "WINDOW_DESTROY": 12,
        "SURFACE_REQUEST": 19,
        "SURFACE_AVAILABLE": 20,
        "FRAME_READY": 21,
        "POINTER_EVENT": 30,
        "KEY_EVENT": 31,
        "GAMEPAD_EVENT": 32,
        "FRAME_PRESENTED": 40,
        "ERROR": 255,
    }
    if types != expected_types:
        failures.append("message type map changed")

    expected_caps = {
        "WINDOW_SURFACE": 1,
        "POINTER": 2,
        "KEYBOARD": 4,
        "GAMEPAD": 8,
        "FRAME_ACK": 16,
        "HOST_BASELINE": 23,
    }
    if caps != expected_caps:
        failures.append("capability map changed")

    expected_direction = {
        "HELLO": "guest-to-host",
        "HELLO_ACK": "host-to-guest",
        "WINDOW_CREATE": "guest-to-host",
        "WINDOW_GEOMETRY": "guest-to-host",
        "WINDOW_DESTROY": "guest-to-host",
        "SURFACE_REQUEST": "guest-to-host",
        "SURFACE_AVAILABLE": "host-to-guest",
        "FRAME_READY": "guest-to-host",
        "POINTER_EVENT": "host-to-guest",
        "KEY_EVENT": "host-to-guest",
        "GAMEPAD_EVENT": "host-to-guest",
        "FRAME_PRESENTED": "host-to-guest",
        "ERROR": "bidirectional",
    }
    if direction != expected_direction:
        failures.append("message direction map changed")

    expected_pointer =
        (input_semantics.get("pointer") or {})
    expected_keyboard =
        (input_semantics.get("keyboard") or {})
    if expected_pointer.get("actions") != {
        "MOVE": 0,
        "DOWN": 1,
        "UP": 2,
        "SCROLL": 3,
    }:
        failures.append(
            "pointer action semantics changed"
        )
    if expected_pointer.get("buttons") != {
        "PRIMARY": 1,
        "SECONDARY": 2,
        "MIDDLE": 4,
    }:
        failures.append(
            "pointer button semantics changed"
        )
    if expected_keyboard.get("actions") != {
        "DOWN": 1,
        "UP": 2,
        "REPEAT": 3,
    }:
        failures.append(
            "keyboard action semantics changed"
        )

    expected_sizes = {
        "WINDOW_CREATE": 28,
        "WINDOW_GEOMETRY": 40,
        "WINDOW_DESTROY": 8,
        "SURFACE_REQUEST": 32,
        "SURFACE_AVAILABLE": 56,
        "FRAME_READY": 32,
        "POINTER_EVENT": 32,
        "KEY_EVENT": 28,
        "FRAME_PRESENTED": 20,
    }
    for name, size in expected_sizes.items():
        if (layouts.get(name) or {}).get("bytes") != size:
            failures.append(
                f"{name} payload size changed"
            )

    expected_z_flags = {
        "NO_CHANGE": 1,
        "TOP": 2,
        "BOTTOM": 4,
        "TOPMOST": 8,
        "NOTOPMOST": 16,
        "AFTER_WINDOW": 32,
    }
    if (
        z_order.get("model") != "single-choice flags"
        or z_order.get("flags") != expected_z_flags
    ):
        failures.append("z-order contract changed")

    surface_request_fields = (
        layouts.get("SURFACE_REQUEST") or {}
    ).get("fields") or []
    if surface_request_fields != [
        "windowId:u64",
        "generation:u64",
        "width:i32",
        "height:i32",
        "pixelFormat:u32",
        "flags:u32",
    ]:
        failures.append(
            "SURFACE_REQUEST v3 field contract changed"
        )

    geometry_fields = (
        layouts.get("WINDOW_GEOMETRY") or {}
    ).get("fields") or []
    if geometry_fields != [
        "windowId:u64",
        "x:i32",
        "y:i32",
        "width:i32",
        "height:i32",
        "visible:u32",
        "zOrderFlags:u32",
        "insertAfterWindowId:u64",
    ]:
        failures.append(
            "WINDOW_GEOMETRY v2 field contract changed"
        )

    if (
        shared.get("pixelFormat") != "BGRA8888"
        or shared.get("pixelFormatId") != 1
        or shared.get("tokenBytes") != 16
        or shared.get("smokeWidth") != 64
        or shared.get("smokeHeight") != 64
        or shared.get("guestPathTemplate")
        != "/tmp/.pocketpc-surface-<tokenHex>.bgra"
    ):
        failures.append(
            "shared framebuffer contract changed"
        )

    kotlin = KOTLIN.read_text(encoding="utf-8")
    payloads = PAYLOADS.read_text(encoding="utf-8")
    framebuffer = FRAMEBUFFER.read_text(encoding="utf-8")
    state_machine = STATE_MACHINE.read_text(encoding="utf-8")
    probe_controller = PROBE_CONTROLLER.read_text(encoding="utf-8")
    surface_registry = SURFACE_REGISTRY.read_text(encoding="utf-8")
    frame_reader = FRAME_READER.read_text(encoding="utf-8")
    host_processor = HOST_PROCESSOR.read_text(encoding="utf-8")
    compositor_model = COMPOSITOR_MODEL.read_text(encoding="utf-8")
    session_controller = SESSION_CONTROLLER.read_text(encoding="utf-8")
    bridge_host = BRIDGE_HOST.read_text(encoding="utf-8")
    frame_preview = FRAME_PREVIEW.read_text(encoding="utf-8")
    runtime_app = RUNTIME_APP.read_text(encoding="utf-8")
    header = HEADER.read_text(encoding="utf-8")
    source = SOURCE.read_text(encoding="utf-8")
    smoke = SMOKE.read_text(encoding="utf-8")
    box64 = BOX64.read_text(encoding="utf-8")
    window_map_header = WINDOW_MAP_HEADER.read_text(encoding="utf-8")
    window_map_source = WINDOW_MAP_SOURCE.read_text(encoding="utf-8")
    window_bridge_header = WINDOW_BRIDGE_HEADER.read_text(encoding="utf-8")
    window_bridge_source = WINDOW_BRIDGE_SOURCE.read_text(encoding="utf-8")
    surface_writer_header = SURFACE_WRITER_HEADER.read_text(encoding="utf-8")
    surface_writer_source = SURFACE_WRITER_SOURCE.read_text(encoding="utf-8")
    visibility_smoke = VISIBILITY_SMOKE.read_text(encoding="utf-8")
    native_integration = NATIVE_INTEGRATION.read_text(encoding="utf-8")

    require_sentinels(
        failures,
        "Kotlin protocol",
        kotlin,
        (
            "HELLO(1)",
            "HELLO_ACK(2)",
            "WINDOW_CREATE(10)",
            "WINDOW_GEOMETRY(11)",
            "WINDOW_DESTROY(12)",
            "SURFACE_REQUEST(19)",
            "SURFACE_AVAILABLE(20)",
            "FRAME_READY(21)",
            "POINTER_EVENT(30)",
            "KEY_EVENT(31)",
            "FRAME_PRESENTED(40)",
            "const val VERSION = 3",
            "const val HEADER_BYTES = 20",
        ),
    )
    require_sentinels(
        failures,
        "Kotlin payload codec",
        payloads,
        (
            "SURFACE_REQUEST_BYTES = 32",
            "SURFACE_AVAILABLE_BYTES = 56",
            "FRAME_READY_BYTES = 32",
            "PIXEL_FORMAT_BGRA8888 = 1",
            "WINDOW_GEOMETRY_BYTES = 40",
            "Z_ORDER_NO_CHANGE = 1 shl 0",
            "Z_ORDER_AFTER_WINDOW = 1 shl 5",
            "insertAfterWindowId",
            "DISPLAY_BRIDGE_Z_ORDER_FLAGS_INVALID",
            "DISPLAY_BRIDGE_Z_ORDER_SELF_REFERENCE",
            "encodeSurfaceRequest",
            "decodeSurfaceRequest",
            "DISPLAY_BRIDGE_SURFACE_FLAGS_INVALID",
            "encodeSurfaceAvailable",
            "decodeSurfaceAvailable",
            "encodeFrameReady",
            "decodeFrameReady",
            "POINTER_ACTION_MOVE = 0",
            "POINTER_ACTION_DOWN = 1",
            "POINTER_ACTION_UP = 2",
            "POINTER_ACTION_SCROLL = 3",
            "KEY_ACTION_DOWN = 1",
            "KEY_ACTION_UP = 2",
            "KEY_ACTION_REPEAT = 3",
            "DISPLAY_BRIDGE_POINTER_BUTTON_MASK_INVALID",
            "encodePointerEvent",
            "encodeKeyEvent",
            "encodeFramePresented",
        ),
    )
    require_sentinels(
        failures,
        "Kotlin v3 state machine",
        state_machine,
        (
            "SurfaceRequested",
            "decodeSurfaceRequest",
            "DISPLAY_BRIDGE_SURFACE_GENERATION_STALE",
            "surfaceGeneration",
            "DISPLAY_BRIDGE_SURFACE_GENERATION_MISMATCH",
        ),
    )
    require_sentinels(
        failures,
        "Kotlin v3 probe ordering",
        probe_controller,
        (
            "DISPLAY_BRIDGE_EXPECTED_SURFACE_REQUEST",
            "SurfaceRequested",
            "RuntimeDisplayHostProcessor",
            "processNext",
            "acknowledgePendingFrame",
            "DISPLAY_BRIDGE_FRAME_READY_IDENTITY_MISMATCH",
            "previewFrame",
        ),
    )
    require_sentinels(
        failures,
        "Kotlin surface registry",
        surface_registry,
        (
            "RuntimeDisplaySurfaceRegistry",
            "MAX_SURFACES = 64",
            "DISPLAY_SURFACE_GENERATION_STALE",
            "DISPLAY_SURFACE_IDENTITY_MISMATCH",
            "DISPLAY_SURFACE_FRAME_STALE",
            "Math.addExact",
            "previous",
            ".framebuffer",
            ".close()",
        ),
    )
    require_sentinels(
        failures,
        "Kotlin frame reader",
        frame_reader,
        (
            "RuntimeDisplayFramePixels",
            "RuntimeDisplayFrameReader",
            "DISPLAY_FRAME_SIZE_CHANGED",
            "ARGB",
        ),
    )
    require_sentinels(
        failures,
        "Kotlin host processor",
        host_processor,
        (
            "RuntimeDisplayHostProcessor",
            "RuntimeDisplayBridgeEndpoint",
            "RuntimeDisplaySurfaceRegistry",
            "DISPLAY_BRIDGE_FRAME_ACK_PENDING",
            "FRAME_STATUS_REJECTED",
            "acknowledgePendingFrame",
            "sendSurfaceAvailable",
        ),
    )
    require_sentinels(
        failures,
        "Kotlin compositor model",
        compositor_model,
        (
            "RuntimeDisplayCompositorModel",
            "RuntimeDisplayCompositorWindow",
            "DISPLAY_COMPOSITOR_FRAME_STALE",
            "WindowGeometryChanged",
            "WindowDestroyed",
        ),
    )
    require_sentinels(
        failures,
        "Kotlin continuous display session",
        session_controller,
        (
            "RuntimeDisplaySessionController",
            "MutableStateFlow",
            "runSession",
            "acknowledgePendingFrame",
            "Dispatchers.IO",
        ),
    )
    require_sentinels(
        failures,
        "Kotlin bridge endpoint and production timeout",
        bridge_host,
        (
            "RuntimeDisplayBridgeEndpoint",
            "RuntimeDisplayBridgePeer",
            "readTimeoutMillis <= 0",
            "socket.soTimeout",
        ),
    )
    require_sentinels(
        failures,
        "Compose display preview",
        frame_preview + runtime_app,
        (
            "RuntimeDisplayFramePreview",
            "Bitmap.createBitmap",
            "previewFrame",
            "probePreviewFrame",
            "aspectRatio",
        ),
    )
    require_sentinels(
        failures,
        "Kotlin framebuffer",
        framebuffer,
        (
            "RuntimeDisplaySharedFramebuffer",
            "validateSmokePattern",
            ".pocketpc-surface-",
            "MAX_FRAMEBUFFER_BYTES",
            "expectedR",
        ),
    )
    require_sentinels(
        failures,
        "guest C header",
        header,
        (
            "#define PDB_MAGIC 0x31424450u",
            "#define PDB_VERSION 3u",
            "#define PDB_WINDOW_GEOMETRY_BYTES 40u",
            "#define PDB_ZORDER_NO_CHANGE (1u << 0)",
            "#define PDB_ZORDER_AFTER_WINDOW (1u << 5)",
            "#define PDB_SURFACE_REQUEST_BYTES 32u",
            "#define PDB_MSG_SURFACE_REQUEST 19u",
            "#define PDB_SURFACE_AVAILABLE_BYTES 56u",
            "#define PDB_FRAME_READY_BYTES 32u",
            "#define PDB_MSG_FRAME_READY 21u",
            "pdb_send_surface_request",
            "pdb_receive_surface_available",
            "pdb_surface_guest_path",
            "pdb_send_frame_ready",
            "pdb_receive_pointer_event",
            "pdb_receive_key_event",
            "pdb_receive_frame_presented",
        ),
    )
    require_sentinels(
        failures,
        "guest C validation",
        source,
        (
            "PDB_CAPABILITY_NOT_NEGOTIATED",
            "PDB_SEQUENCE_INVALID",
            "PDB_SURFACE_REQUEST_INVALID",
            "PDB_SURFACE_PAYLOAD_INVALID",
            "PDB_FRAME_READY_INVALID",
            "PDB_POINTER_PAYLOAD_INVALID",
            "PDB_KEY_PAYLOAD_INVALID",
            "PDB_FRAME_ACK_PAYLOAD_INVALID",
            "PDB_WINDOW_GEOMETRY_INVALID",
            "PDB_SOCKET_TIMEOUT_SECONDS",
            "SO_RCVTIMEO",
            "SO_SNDTIMEO",
            "invalidate_connection",
            "PDB_SEND_FAILED",
            "PDB_RECEIVE_FAILED",
            "PDB_POINTER_BUTTON_ALLOWED",
            "pdb_peek_message_type",
            "pdb_receive_host_event",
        ),
    )
    require_sentinels(
        failures,
        "guest bridge smoke",
        smoke,
        (
            "POCKETPC_DISPLAY_BRIDGE_SMOKE_OK",
            "POCKETPC_DISPLAY_BRIDGE_FRAME_WRITTEN_OK",
            "pdb_send_surface_request",
            "pdb_receive_surface_available",
            "pdb_send_frame_ready",
            "MAP_SHARED",
            "MS_SYNC",
        ),
    )
    require_sentinels(
        failures,
        "Wine window map",
        window_map_header + window_map_source,
        (
            "PDB_WINE_WINDOW_LIMIT 64u",
            "pdb_wine_window_register",
            "pdb_wine_window_lookup",
            "pdb_wine_window_handle_for_id",
            "pdb_wine_window_state",
            "pdb_wine_window_mark_sent",
            "PDB_WINE_WINDOW_CREATE_SENT",
            "PDB_WINE_WINDOW_DESTROY_SENT",
            "pdb_wine_window_has_children",
            "pdb_wine_window_unregister",
        ),
    )
    require_sentinels(
        failures,
        "Wine window lifecycle adapter",
        window_bridge_header + window_bridge_source,
        (
            "pdb_wine_window_bridge_init",
            "pdb_wine_window_bridge_create",
            "pdb_wine_window_bridge_geometry",
            "pdb_wine_window_bridge_destroy",
            "pdb_wine_window_bridge_handle_for_id",
            "PDB_WINE_WINDOW_HAS_CHILDREN",
            "z_order_flags",
            "insert_after_window_id",
        ),
    )
    require_sentinels(
        failures,
        "Shared surface writer",
        surface_writer_header + surface_writer_source,
        (
            "pdb_surface_writer_open",
            "O_NOFOLLOW",
            "st.st_nlink != 1",
            "MAP_SHARED",
            "pdb_surface_writer_copy_bgra",
            "pdb_surface_writer_commit",
            "atomic_thread_fence",
            "memory_order_release",
            "PDB_SURFACE_FRAME_ID_EXHAUSTED",
            "pdb_send_frame_ready",
        ),
    )
    require_sentinels(
        failures,
        "Cross-process surface visibility smoke",
        visibility_smoke + native_integration,
        (
            "surface_writer_visibility_smoke.c",
            "POCKETPC_SURFACE_VISIBILITY_SMOKE_OK",
            "fork()",
            "waitpid",
            "surface_visibility=cross-process-native-pass",
        ),
    )
    if "MS_SYNC" in surface_writer_source:
        failures.append(
            "surface writer must not force MS_SYNC on the frame hot path"
        )

    require_sentinels(
        failures,
        "Box64 bridge builder",
        box64,
        (
            "pocketpc_display_bridge.h",
            "pocketpc_display_bridge.c",
            "display_bridge_smoke.c",
            "displayBridgeSources",
            '"protocolVersion": 3',
        ),
    )

    if failures:
        print(
            "DISPLAY_BRIDGE_PROTOCOL_LOCK_FAILED",
            file=sys.stderr,
        )
        for failure in failures:
            print(
                "- " + failure,
                file=sys.stderr,
            )
        return 1

    print("DISPLAY_BRIDGE_PROTOCOL_LOCK_OK")
    print("protocol_version=3")
    print("shared_framebuffer=BGRA8888")
    print("runtime_integration_evidence=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
