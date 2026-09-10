#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
HOST_CPP = ROOT / "app/src/main/cpp/graphics_seqpacket_session.cpp"
HOST_KT = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GraphicsSeqpacketSessionHost.kt"
CMAKE = ROOT / "app/src/main/cpp/CMakeLists.txt"
GUEST_H = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_graphics_transport.h"
GUEST_C = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_graphics_transport.c"
CONTRACT = ROOT / "app/src/main/java/dev/pocketpc/core/runtime/GuestGraphicsTransportContract.kt"


def require(failures: list[str], text: str, markers: tuple[str, ...]) -> None:
    for marker in markers:
        if marker not in text:
            failures.append("missing: " + marker)


def main() -> int:
    failures: list[str] = []
    host_cpp = HOST_CPP.read_text(encoding="utf-8")
    host_kt = HOST_KT.read_text(encoding="utf-8")
    cmake = CMAKE.read_text(encoding="utf-8")
    guest_h = GUEST_H.read_text(encoding="utf-8")
    guest_c = GUEST_C.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")

    require(failures, host_cpp, (
        "SOCK_SEQPACKET | SOCK_CLOEXEC",
        "SO_PEERCRED",
        "ConstantTimeEqual",
        "kHandshakeBytes = 48",
        "kTokenBytes = 32",
        "peer.pid",
    ))
    require(failures, host_kt, (
        "SecureRandom",
        "POCKETPC_GRAPHICS_SOCKET_NAME",
        "POCKETPC_GRAPHICS_SESSION_TOKEN",
        "POCKETPC_GRAPHICS_SESSION_PROTOCOL",
        "Blocking. Call from the runtime IO executor",
    ))
    require(failures, cmake, ("graphics_seqpacket_session.cpp",))
    require(failures, guest_h, (
        "PGT_SESSION_MAGIC",
        "PGT_SESSION_TOKEN_BYTES 32u",
        "pgt_connect_authenticated_session",
    ))
    require(failures, guest_c, (
        "socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC",
        "pgt_decode_token",
        "getpid()",
        "MSG_NOSIGNAL",
        "pgt_connect_authenticated_session_from_environment",
    ))
    require(failures, contract, (
        "authenticatedSessionHandshakePrimitiveImplemented = true",
        "androidSeqpacketSessionHostImplemented = true",
        "guestReceiveImplemented = false",
        "guestImportImplemented = false",
        "synchronizationImplemented = false",
    ))

    forbidden = (
        "guestReceiveImplemented = true",
        "guestImportImplemented = true",
        "synchronizationImplemented = true",
    )
    for marker in forbidden:
        if marker in contract:
            failures.append("runtime integration promoted without execution: " + marker)

    if failures:
        print("GRAPHICS_SESSION_AUTH_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    print("GRAPHICS_SESSION_AUTH_POLICY_OK")
    print("authenticated_session_primitives_implemented=true")
    print("runtime_integration=false")
    print("vulkan_import_execution=false")
    print("roblox=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
