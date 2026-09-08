#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "third_party/wine/POCKETPC_DISPLAY_BRIDGE_PROTOCOL.json"
KOTLIN = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/RuntimeDisplayBridgeProtocol.kt"
BOX64 = ROOT / "scripts/build-box64-aarch64.py"


def main() -> int:
    failures: list[str] = []
    try:
        lock = json.loads(LOCK.read_text(encoding="utf-8"))
    except Exception as error:
        print(f"DISPLAY_BRIDGE_PROTOCOL_LOCK_FAILED\n- lock: {error}", file=sys.stderr)
        return 1

    wire = lock.get("wire") or {}
    hello = lock.get("hello") or {}
    types = lock.get("messageTypes") or {}
    caps = lock.get("capabilities") or {}

    expected = {
        "magicHex": "0x31424450",
        "version": 1,
        "headerBytes": 20,
        "maxPayloadBytes": 1024 * 1024,
        "tokenBytes": 32,
        "identityBytes": 64,
        "helloPayloadBytes": 100,
        "ackPayloadBytes": 4,
    }

    if wire.get("magicHex") != expected["magicHex"]:
        failures.append("wire magic changed")
    if wire.get("version") != expected["version"]:
        failures.append("wire version changed")
    if wire.get("headerBytes") != expected["headerBytes"]:
        failures.append("header size changed")
    if wire.get("maxPayloadBytes") != expected["maxPayloadBytes"]:
        failures.append("max payload changed")
    if hello.get("tokenBytes") != expected["tokenBytes"]:
        failures.append("token size changed")
    if hello.get("runtimeIdentityAsciiBytes") != expected["identityBytes"]:
        failures.append("runtime identity size changed")
    if hello.get("payloadBytes") != expected["helloPayloadBytes"]:
        failures.append("hello payload size changed")
    if hello.get("ackPayloadBytes") != expected["ackPayloadBytes"]:
        failures.append("ack payload size changed")

    expected_types = {
        "HELLO": 1,
        "HELLO_ACK": 2,
        "WINDOW_CREATE": 10,
        "WINDOW_GEOMETRY": 11,
        "WINDOW_DESTROY": 12,
        "SURFACE_AVAILABLE": 20,
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

    kotlin = KOTLIN.read_text(encoding="utf-8")
    kotlin_sentinels = (
        "HELLO(1)",
        "HELLO_ACK(2)",
        "WINDOW_CREATE(10)",
        "WINDOW_GEOMETRY(11)",
        "WINDOW_DESTROY(12)",
        "SURFACE_AVAILABLE(20)",
        "POINTER_EVENT(30)",
        "KEY_EVENT(31)",
        "GAMEPAD_EVENT(32)",
        "FRAME_PRESENTED(40)",
        "ERROR(255)",
        "const val VERSION = 1",
        "const val HEADER_BYTES = 20",
        "1024 * 1024",
        "0x31424450",
        "const val TOKEN_BYTES = 32",
        "ByteArray(64)",
        "const val WINDOW_SURFACE =",
        "const val POINTER =",
        "const val KEYBOARD =",
        "const val GAMEPAD =",
        "const val FRAME_ACK =",
    )
    for sentinel in kotlin_sentinels:
        if sentinel not in kotlin:
            failures.append("Kotlin protocol missing sentinel: " + sentinel)

    box64 = BOX64.read_text(encoding="utf-8")
    client_sentinels = (
        "#define PDB_MAGIC 0x31424450u",
        "#define PDB_VERSION 1u",
        "#define PDB_HELLO 1u",
        "#define PDB_HELLO_ACK 2u",
        "#define PDB_TOKEN_BYTES 32u",
        "#define PDB_IDENTITY_BYTES 64u",
        "#define PDB_HELLO_BYTES 100u",
        "#define PDB_HEADER_BYTES 20u",
        "POCKETPC_DISPLAY_BRIDGE_SMOKE_OK",
    )
    for sentinel in client_sentinels:
        if sentinel not in box64:
            failures.append("x86_64 bridge client missing sentinel: " + sentinel)

    if failures:
        print("DISPLAY_BRIDGE_PROTOCOL_LOCK_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    print("DISPLAY_BRIDGE_PROTOCOL_LOCK_OK")
    print("protocol_version=1")
    print("runtime_integration_evidence=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
