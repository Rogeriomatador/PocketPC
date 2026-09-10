#!/usr/bin/env python3
"""Pure protocol test for PocketPC Wine surface broker; does not execute Wine."""

from __future__ import annotations
import os
from pathlib import Path
import socket
import struct
import time

from pocketpc_wine_surface_broker import (
    MAGIC,
    VERSION,
    MSG_HELLO,
    MSG_HELLO_ACK,
    MSG_WINDOW_CREATE,
    MSG_WINDOW_DESTROY,
    MSG_SURFACE_REQUEST,
    MSG_SURFACE_AVAILABLE,
    MSG_FRAME_READY,
    MSG_POINTER_EVENT,
    MSG_KEY_EVENT,
    MSG_FRAME_PRESENTED,
    SurfaceSmokeBroker,
    read_frame,
)

def send(conn:socket.socket,msg_type:int,sequence:int,payload:bytes=b"")->None:
    conn.sendall(struct.pack("<IHHIQ",MAGIC,VERSION,msg_type,len(payload),sequence)+payload)

def wait_snapshot(broker:SurfaceSmokeBroker,key:str,minimum:int|bool,timeout:float=2.0)->dict[str,object]:
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        snapshot=broker.snapshot()
        value=snapshot[key]
        if isinstance(minimum,bool):
            if value is minimum:return snapshot
        elif isinstance(value,int) and value>=minimum:
            return snapshot
        broker.activity.wait(0.02)
        broker.activity.clear()
    return broker.snapshot()

def main()->int:
    token=bytes(range(32))
    identity=b"a"*64
    broker=SurfaceSmokeBroker(token,identity)
    broker.start()
    client=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
    client.connect("\0"+broker.socket_name)

    hello=token+identity+struct.pack("<I",23)
    send(client,MSG_HELLO,0,hello)
    msg_type,sequence,payload=read_frame(client)
    assert (msg_type,sequence)==(MSG_HELLO_ACK,0)
    assert struct.unpack("<I",payload)[0]==23

    window_id=101
    send(client,MSG_WINDOW_CREATE,1,struct.pack("<QQIii",window_id,0,0,640,360))
    generation=7
    send(client,MSG_SURFACE_REQUEST,2,struct.pack("<QQiiII",window_id,generation,64,48,1,0))
    msg_type,sequence,payload=read_frame(client)
    assert (msg_type,sequence)==(MSG_SURFACE_AVAILABLE,1)
    win,surface_id,gen,width,height,stride,pixel_format,surface_token=struct.unpack("<QQQiiiI16s",payload)
    assert (win,gen,width,height,stride,pixel_format)==(window_id,generation,64,48,256,1)

    surface_path=Path("/tmp/.pocketpc-surface-"+surface_token.hex()+".bgra")
    assert surface_path.is_file()
    pixels=bytearray(surface_path.read_bytes())
    assert len(pixels)==stride*height
    for offset in range(0,len(pixels),4):
        pixels[offset:offset+4]=bytes((12,34,56,255))
    surface_path.write_bytes(pixels)

    frame_id=1
    send(client,MSG_FRAME_READY,3,struct.pack("<QQQQ",window_id,surface_id,generation,frame_id))

    msg_type,sequence,pointer=read_frame(client)
    assert (msg_type,sequence)==(MSG_POINTER_EVENT,2)
    assert struct.unpack("<QIiiIiI",pointer)==(window_id,1,63,47,1,0,0)

    msg_type,sequence,key=read_frame(client)
    assert (msg_type,sequence)==(MSG_KEY_EVENT,3)
    assert struct.unpack("<QIIIII",key)==(window_id,1,65,0,0,1)

    msg_type,sequence,ack=read_frame(client)
    assert (msg_type,sequence)==(MSG_FRAME_PRESENTED,4)
    assert struct.unpack("<QQQQI",ack)==(window_id,surface_id,generation,frame_id,0)

    send(client,MSG_WINDOW_DESTROY,4,struct.pack("<Q",window_id))
    client.close()

    snapshot=wait_snapshot(broker,"destroyCount",1)
    assert snapshot["handshakeCount"]==1
    assert snapshot["windowCreateCount"]==1
    assert snapshot["surfaceCount"]==1
    assert snapshot["frameCount"]==1
    assert snapshot["inputSentCount"]==1
    assert snapshot["destroyCount"]==1
    assert snapshot["pixelsObserved"] is True
    assert snapshot["errors"]==[]
    broker.close()
    assert not surface_path.exists()

    bad=SurfaceSmokeBroker(token,identity)
    bad.start()
    bad_client=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
    bad_client.connect("\0"+bad.socket_name)
    bad_hello=os.urandom(32)+identity+struct.pack("<I",23)
    send(bad_client,MSG_HELLO,0,bad_hello)
    bad_client.close()
    deadline=time.monotonic()+2
    while time.monotonic()<deadline and not bad.snapshot()["errors"]:
        bad.activity.wait(0.02);bad.activity.clear()
    assert any("HELLO token mismatch" in str(error) for error in bad.snapshot()["errors"])
    bad.close()

    print("WINE_POCKETPC_SURFACE_BROKER_TEST_OK")
    print("protocol_v4_handshake=accepted")
    print("window_create=accepted")
    print("surface_available=emitted")
    print("nonzero_shared_pixels=observed")
    print("pointer_key_and_frame_ack=emitted")
    print("window_destroy=accepted")
    print("wrong_token=rejected")
    print("wine_execution=false")
    print("physical_validation=false")
    return 0

if __name__=="__main__":
    raise SystemExit(main())
