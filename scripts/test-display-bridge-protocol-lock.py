#!/usr/bin/env python3
from __future__ import annotations
import json
from pathlib import Path
import sys
ROOT=Path(__file__).resolve().parents[1]
LOCK=ROOT/'third_party/wine/POCKETPC_DISPLAY_BRIDGE_PROTOCOL.json'
KOTLIN=ROOT/'app/src/main/java/dev/pocketpc/core/runtime/RuntimeDisplayBridgeProtocol.kt'
PAYLOADS=ROOT/'app/src/main/java/dev/pocketpc/core/runtime/RuntimeDisplayBridgePayloads.kt'
BOX64=ROOT/'scripts/build-box64-aarch64.py'
HEADER=ROOT/'third_party/wine/pocketpc-display-bridge/pocketpc_display_bridge.h'
SOURCE=ROOT/'third_party/wine/pocketpc-display-bridge/pocketpc_display_bridge.c'
SMOKE=ROOT/'third_party/wine/pocketpc-display-bridge/display_bridge_smoke.c'

def main()->int:
    failures=[]
    try: lock=json.loads(LOCK.read_text())
    except Exception as error:
        print('DISPLAY_BRIDGE_PROTOCOL_LOCK_FAILED\n- lock: '+str(error),file=sys.stderr); return 1
    wire=lock.get('wire') or {}; hello=lock.get('hello') or {}; types=lock.get('messageTypes') or {}; caps=lock.get('capabilities') or {}
    checks=[(wire.get('magicHex'),'0x31424450','wire magic'),(wire.get('version'),1,'version'),(wire.get('headerBytes'),20,'header'),(wire.get('maxPayloadBytes'),1048576,'max payload'),(hello.get('tokenBytes'),32,'token'),(hello.get('runtimeIdentityAsciiBytes'),64,'identity'),(hello.get('payloadBytes'),100,'hello'),(hello.get('ackPayloadBytes'),4,'ack')]
    for actual,expected,name in checks:
        if actual!=expected: failures.append(name+' changed')
    expected_types={'HELLO':1,'HELLO_ACK':2,'WINDOW_CREATE':10,'WINDOW_GEOMETRY':11,'WINDOW_DESTROY':12,'SURFACE_AVAILABLE':20,'POINTER_EVENT':30,'KEY_EVENT':31,'GAMEPAD_EVENT':32,'FRAME_PRESENTED':40,'ERROR':255}
    expected_caps={'WINDOW_SURFACE':1,'POINTER':2,'KEYBOARD':4,'GAMEPAD':8,'FRAME_ACK':16,'HOST_BASELINE':23}
    if types!=expected_types: failures.append('message type map changed')
    if caps!=expected_caps: failures.append('capability map changed')
    expected_direction={'HELLO':'guest-to-host','HELLO_ACK':'host-to-guest','WINDOW_CREATE':'guest-to-host','WINDOW_GEOMETRY':'guest-to-host','WINDOW_DESTROY':'guest-to-host','SURFACE_AVAILABLE':'host-to-guest','POINTER_EVENT':'host-to-guest','KEY_EVENT':'host-to-guest','GAMEPAD_EVENT':'host-to-guest','FRAME_PRESENTED':'host-to-guest','ERROR':'bidirectional'}
    if lock.get('direction')!=expected_direction: failures.append('message direction map changed')
    expected_sizes={'WINDOW_CREATE':28,'WINDOW_GEOMETRY':32,'WINDOW_DESTROY':8,'POINTER_EVENT':32,'KEY_EVENT':28,'FRAME_PRESENTED':20}
    layouts=lock.get('payloadLayouts') or {}
    for name,size in expected_sizes.items():
        if (layouts.get(name) or {}).get('bytes')!=size: failures.append(name+' payload size changed')
    kotlin=KOTLIN.read_text(); payloads=PAYLOADS.read_text(); header=HEADER.read_text(); source=SOURCE.read_text(); smoke=SMOKE.read_text(); box64=BOX64.read_text()
    for s in ('HELLO(1)','HELLO_ACK(2)','WINDOW_CREATE(10)','WINDOW_GEOMETRY(11)','WINDOW_DESTROY(12)','POINTER_EVENT(30)','KEY_EVENT(31)','FRAME_PRESENTED(40)','const val VERSION = 1','const val HEADER_BYTES = 20'):
        if s not in kotlin: failures.append('Kotlin protocol missing: '+s)
    for s in ('WINDOW_CREATE_BYTES = 28','WINDOW_GEOMETRY_BYTES = 32','POINTER_EVENT_BYTES = 32','KEY_EVENT_BYTES = 28','FRAME_PRESENTED_BYTES = 20','encodePointerEvent','encodeKeyEvent','encodeFramePresented'):
        if s not in payloads: failures.append('Kotlin payload codec missing: '+s)
    for s in ('#define PDB_MAGIC 0x31424450u','#define PDB_VERSION 1u','#define PDB_POINTER_EVENT_BYTES 32u','#define PDB_KEY_EVENT_BYTES 28u','#define PDB_FRAME_PRESENTED_BYTES 20u','pdb_receive_pointer_event','pdb_receive_key_event','pdb_receive_frame_presented'):
        if s not in header: failures.append('guest C header missing: '+s)
    for s in ('PDB_CAPABILITY_NOT_NEGOTIATED','PDB_SEQUENCE_INVALID','PDB_POINTER_PAYLOAD_INVALID','PDB_KEY_PAYLOAD_INVALID','PDB_FRAME_ACK_PAYLOAD_INVALID'):
        if s not in source: failures.append('guest C validation missing: '+s)
    if 'POCKETPC_DISPLAY_BRIDGE_SMOKE_OK' not in smoke: failures.append('guest smoke sentinel missing')
    for s in ('pocketpc_display_bridge.h','pocketpc_display_bridge.c','display_bridge_smoke.c','displayBridgeSources'):
        if s not in box64: failures.append('Box64 builder missing: '+s)
    if failures:
        print('DISPLAY_BRIDGE_PROTOCOL_LOCK_FAILED',file=sys.stderr)
        for failure in failures: print('- '+failure,file=sys.stderr)
        return 1
    print('DISPLAY_BRIDGE_PROTOCOL_LOCK_OK'); print('protocol_version=1'); print('runtime_integration_evidence=false'); return 0
if __name__=='__main__': raise SystemExit(main())
