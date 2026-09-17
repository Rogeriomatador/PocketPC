#!/usr/bin/env python3
"""Protocol-v4 broker used only by PocketPC host Wine surface/input smoke tests."""

from __future__ import annotations
from dataclasses import dataclass
import os
from pathlib import Path
import socket
import struct
import threading

MAGIC=0x31424450
VERSION=4
MAX_PAYLOAD=1024*1024
REQUIRED_CAPS=23
MAX_ACTIVE_PEERS=8
MAX_SURFACES=16
MAX_SURFACE_DIMENSION=4096
MAX_SURFACE_BYTES=64*1024*1024
MAX_TOTAL_SURFACE_BYTES=128*1024*1024
MSG_HELLO=1
MSG_HELLO_ACK=2
MSG_WINDOW_CREATE=10
MSG_WINDOW_GEOMETRY=11
MSG_WINDOW_DESTROY=12
MSG_SURFACE_REQUEST=19
MSG_SURFACE_AVAILABLE=20
MSG_FRAME_READY=21
MSG_POINTER_EVENT=30
MSG_KEY_EVENT=31
MSG_FRAME_PRESENTED=40


def read_exact(conn:socket.socket,size:int)->bytes:
    out=bytearray()
    while len(out)<size:
        block=conn.recv(size-len(out))
        if not block:
            raise RuntimeError("peer closed while reading display bridge frame")
        out.extend(block)
    return bytes(out)
def read_frame(conn:socket.socket)->tuple[int,int,bytes]:
    header=read_exact(conn,20)
    magic,version,msg_type,size,sequence=struct.unpack("<IHHIQ",header)
    if magic!=MAGIC or version!=VERSION or size>MAX_PAYLOAD:
        raise RuntimeError("invalid display bridge frame header")
    return msg_type,sequence,read_exact(conn,size)
def write_frame(conn:socket.socket,msg_type:int,sequence:int,payload:bytes=b"")->None:
    if len(payload)>MAX_PAYLOAD:
        raise RuntimeError("display bridge payload exceeds protocol maximum")
    conn.sendall(struct.pack("<IHHIQ",MAGIC,VERSION,msg_type,len(payload),sequence)+payload)

@dataclass
class SurfaceState:
    window_id:int
    surface_id:int
    generation:int
    width:int
    height:int
    stride:int
    token:bytes
    path:Path
    bytes:int

class SurfaceSmokeBroker:
    def __init__(self,token:bytes,identity:bytes):
        if len(token)!=32 or len(identity)!=64:
            raise ValueError("broker token/identity size invalid")
        self.token=token
        self.identity=identity
        self.socket_name="pocketpc.wine.surface."+os.urandom(8).hex()
        self.server=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
        self.server.bind("\0"+self.socket_name)
        self.server.listen(MAX_ACTIVE_PEERS)
        self.server.settimeout(0.25)
        self.stop=threading.Event()
        self.activity=threading.Event()
        self.lock=threading.Lock()
        self.accept_thread=threading.Thread(target=self._accept_loop,daemon=True)
        self.workers:list[threading.Thread]=[]
        self.errors:list[str]=[]
        self.surface_paths:list[Path]=[]
        self.active_peers=0
        self.handshake_count=0
        self.window_create_count=0
        self.surface_count=0
        self.allocated_surface_bytes=0
        self.frame_count=0
        self.input_sent_count=0
        self.destroy_count=0
        self.pixels_observed=False
        self.next_surface_id=1

    def start(self)->None:
        self.accept_thread.start()

    def close(self)->None:
        self.stop.set()
        try:self.server.close()
        except OSError:pass
        self.accept_thread.join(timeout=2)
        for worker in list(self.workers):worker.join(timeout=2)
        for path in list(self.surface_paths):
            try:path.unlink()
            except FileNotFoundError:pass
            except OSError:pass

    def snapshot(self)->dict[str,object]:
        with self.lock:
            return {
                "activePeers":self.active_peers,
                "handshakeCount":self.handshake_count,
                "windowCreateCount":self.window_create_count,
                "surfaceCount":self.surface_count,
                "allocatedSurfaceBytes":self.allocated_surface_bytes,
                "frameCount":self.frame_count,
                "inputSentCount":self.input_sent_count,
                "destroyCount":self.destroy_count,
                "pixelsObserved":self.pixels_observed,
                "errors":list(self.errors),
            }

    def _record_error(self,exc:Exception)->None:
        with self.lock:self.errors.append(f"{exc.__class__.__name__}:{exc}")
        self.activity.set()

    def _accept_loop(self)->None:
        while not self.stop.is_set():
            try:conn,_=self.server.accept()
            except TimeoutError:continue
            except OSError:
                if self.stop.is_set():return
                raise
            with self.lock:
                if self.active_peers>=MAX_ACTIVE_PEERS:
                    allowed=False
                else:
                    self.active_peers+=1
                    allowed=True
            if not allowed:
                try:conn.close()
                finally:self._record_error(RuntimeError("active peer limit exceeded"))
                continue
            worker=threading.Thread(target=self._connection_worker,args=(conn,),daemon=True)
            with self.lock:self.workers.append(worker)
            worker.start()

    def _connection_worker(self,conn:socket.socket)->None:
        try:self._handle_connection(conn)
        finally:
            with self.lock:self.active_peers=max(0,self.active_peers-1)
            self.activity.set()

    def _reserve_surface(self,byte_count:int)->int:
        if byte_count<=0 or byte_count>MAX_SURFACE_BYTES:
            raise RuntimeError("surface byte size exceeds per-surface limit")
        with self.lock:
            if self.surface_count>=MAX_SURFACES:
                raise RuntimeError("surface count limit exceeded")
            if self.allocated_surface_bytes+byte_count>MAX_TOTAL_SURFACE_BYTES:
                raise RuntimeError("total surface byte limit exceeded")
            surface_id=self.next_surface_id
            self.next_surface_id+=1
            self.surface_count+=1
            self.allocated_surface_bytes+=byte_count
            return surface_id

    def _rollback_surface_reservation(self,byte_count:int)->None:
        with self.lock:
            self.surface_count=max(0,self.surface_count-1)
            self.allocated_surface_bytes=max(0,self.allocated_surface_bytes-byte_count)

    def _create_surface_file(self,path:Path,byte_count:int)->None:
        flags=os.O_WRONLY|os.O_CREAT|os.O_EXCL
        if hasattr(os,"O_CLOEXEC"):flags|=os.O_CLOEXEC
        fd=os.open(path,flags,0o600)
        try:os.ftruncate(fd,byte_count)
        finally:os.close(fd)

    def _handle_connection(self,conn:socket.socket)->None:
        surfaces:dict[int,SurfaceState]={}
        known_windows:set[int]=set()
        expected_guest_sequence=1
        host_sequence=1
        try:
            with conn:
                conn.settimeout(0.5)
                msg_type,sequence,payload=read_frame(conn)
                if (msg_type,sequence)!=(MSG_HELLO,0):raise RuntimeError(f"unexpected HELLO frame type={msg_type} seq={sequence}")
                if len(payload)!=100:raise RuntimeError(f"HELLO payload size invalid: {len(payload)}")
                if payload[:32]!=self.token:raise RuntimeError("HELLO token mismatch")
                if payload[32:96]!=self.identity:raise RuntimeError("HELLO runtime identity mismatch")
                guest_caps=struct.unpack("<I",payload[96:])[0]
                if guest_caps & REQUIRED_CAPS != REQUIRED_CAPS:raise RuntimeError(f"guest capabilities incomplete: {guest_caps}")
                write_frame(conn,MSG_HELLO_ACK,0,struct.pack("<I",REQUIRED_CAPS))
                with self.lock:self.handshake_count+=1
                self.activity.set()

                while not self.stop.is_set():
                    try:msg_type,sequence,payload=read_frame(conn)
                    except TimeoutError:continue
                    except ConnectionResetError:return
                    except RuntimeError as error:
                        if "peer closed" in str(error):return
                        raise
                    if sequence!=expected_guest_sequence:raise RuntimeError(f"guest sequence mismatch: expected={expected_guest_sequence} actual={sequence}")
                    expected_guest_sequence+=1

                    if msg_type==MSG_WINDOW_CREATE:
                        if len(payload)!=28:raise RuntimeError("WINDOW_CREATE size invalid")
                        window_id,parent_id,flags,width,height=struct.unpack("<QQIii",payload)
                        if window_id==0 or width<=0 or height<=0:raise RuntimeError("WINDOW_CREATE values invalid")
                        if parent_id and parent_id not in known_windows:raise RuntimeError("WINDOW_CREATE parent unknown to broker")
                        _=flags
                        known_windows.add(window_id)
                        with self.lock:self.window_create_count+=1
                        self.activity.set();continue

                    if msg_type==MSG_WINDOW_GEOMETRY:
                        if len(payload)!=40:raise RuntimeError("WINDOW_GEOMETRY size invalid")
                        window_id,x,y,width,height,visible,z_flags,after_id=struct.unpack("<QiiiiIIQ",payload)
                        if window_id not in known_windows or width<=0 or height<=0:raise RuntimeError("WINDOW_GEOMETRY values invalid")
                        _=(x,y,visible,z_flags,after_id);continue

                    if msg_type==MSG_SURFACE_REQUEST:
                        if len(payload)!=32:raise RuntimeError("SURFACE_REQUEST size invalid")
                        window_id,generation,width,height,pixel_format,flags=struct.unpack("<QQiiII",payload)
                        if window_id not in known_windows or generation==0:raise RuntimeError("SURFACE_REQUEST window/generation invalid")
                        if width<=0 or height<=0 or width>MAX_SURFACE_DIMENSION or height>MAX_SURFACE_DIMENSION:
                            raise RuntimeError("SURFACE_REQUEST dimensions invalid")
                        if pixel_format!=1 or flags!=0:raise RuntimeError("SURFACE_REQUEST format/flags invalid")
                        stride=width*4
                        byte_count=stride*height
                        surface_id=self._reserve_surface(byte_count)
                        token=os.urandom(16)
                        path=Path("/tmp/.pocketpc-surface-"+token.hex()+".bgra")
                        try:self._create_surface_file(path,byte_count)
                        except Exception:
                            self._rollback_surface_reservation(byte_count)
                            raise
                        surface=SurfaceState(window_id,surface_id,generation,width,height,stride,token,path,byte_count)
                        surfaces[window_id]=surface
                        with self.lock:self.surface_paths.append(path)
                        reply=struct.pack("<QQQiiiI16s",window_id,surface_id,generation,width,height,stride,1,token)
                        write_frame(conn,MSG_SURFACE_AVAILABLE,host_sequence,reply);host_sequence+=1
                        self.activity.set();continue

                    if msg_type==MSG_FRAME_READY:
                        if len(payload)!=32:raise RuntimeError("FRAME_READY size invalid")
                        window_id,surface_id,generation,frame_id=struct.unpack("<QQQQ",payload)
                        surface=surfaces.get(window_id)
                        if surface is None:raise RuntimeError("FRAME_READY has no broker surface")
                        if (surface_id,generation)!=(surface.surface_id,surface.generation) or frame_id==0:raise RuntimeError("FRAME_READY identity invalid")
                        pixels=surface.path.read_bytes()
                        if len(pixels)!=surface.bytes:raise RuntimeError("shared surface size changed unexpectedly")
                        if not any(pixels):raise RuntimeError("FRAME_READY arrived but shared surface remained all zero")
                        with self.lock:
                            self.frame_count+=1
                            self.pixels_observed=True
                        x=min(100,max(0,surface.width-1));y=min(80,max(0,surface.height-1))
                        pointer=struct.pack("<QIiiIiI",window_id,1,x,y,1,0,0)
                        key=struct.pack("<QIIIII",window_id,1,65,0,0,1)
                        ack=struct.pack("<QQQQI",window_id,surface_id,generation,frame_id,0)
                        write_frame(conn,MSG_POINTER_EVENT,host_sequence,pointer);host_sequence+=1
                        write_frame(conn,MSG_KEY_EVENT,host_sequence,key);host_sequence+=1
                        write_frame(conn,MSG_FRAME_PRESENTED,host_sequence,ack);host_sequence+=1
                        with self.lock:self.input_sent_count+=1
                        self.activity.set();continue

                    if msg_type==MSG_WINDOW_DESTROY:
                        if len(payload)!=8:raise RuntimeError("WINDOW_DESTROY size invalid")
                        window_id=struct.unpack("<Q",payload)[0]
                        if window_id not in known_windows:raise RuntimeError("WINDOW_DESTROY window unknown")
                        known_windows.discard(window_id)
                        with self.lock:self.destroy_count+=1
                        self.activity.set();continue

                    raise RuntimeError(f"unexpected guest message type={msg_type}")
        except (ConnectionResetError,BrokenPipeError):
            return
        except Exception as exc:
            if not self.stop.is_set():self._record_error(exc)