#!/usr/bin/env python3
from __future__ import annotations
import importlib.util
import socket
import struct
import time
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
SCRIPT=ROOT/"scripts/run-wine-pocketpc-driver-load-smoke.py"
spec=importlib.util.spec_from_file_location("pocketpc_load_smoke",SCRIPT)
module=importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)

def send_hello(name:str, token:bytes, identity:bytes, caps:int=23)->tuple[int,int,bytes]:
    client=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
    client.connect("\0"+name)
    payload=token+identity+struct.pack("<I",caps)
    header=struct.pack("<IHHIQ",module.MAGIC,module.VERSION,1,len(payload),0)
    client.sendall(header+payload)
    msg_type,sequence,reply=module.read_frame(client)
    client.close()
    return msg_type,sequence,reply

def main()->int:
    token=bytes(range(32))
    identity=b"a"*64
    broker=module.RegistrationBroker(token,identity)
    broker.start()
    msg_type,sequence,reply=send_hello(broker.socket_name,token,identity)
    assert (msg_type,sequence)==(2,0)
    assert struct.unpack("<I",reply)[0]==23
    for _ in range(20):
        if broker.handshake.is_set(): break
        time.sleep(0.01)
    assert broker.handshake.is_set()
    assert broker.error is None
    broker.close()

    bad=module.RegistrationBroker(token,identity)
    bad.start()
    client=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
    client.connect("\0"+bad.socket_name)
    wrong=b"x"*32+identity+struct.pack("<I",23)
    client.sendall(struct.pack("<IHHIQ",module.MAGIC,module.VERSION,1,len(wrong),0)+wrong)
    client.close()
    for _ in range(50):
        if bad.handshake.is_set(): break
        time.sleep(0.01)
    assert bad.error is not None and "token mismatch" in bad.error
    bad.close()

    print("WINE_POCKETPC_DRIVER_LOAD_BROKER_TEST_OK")
    print("valid_protocol_v4_hello=accepted")
    print("hello_ack_caps=23")
    print("wrong_token=rejected")
    print("wine_execution=false")
    print("physical_validation=false")
    return 0

if __name__=="__main__":
    raise SystemExit(main())
