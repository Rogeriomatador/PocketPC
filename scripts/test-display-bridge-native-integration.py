#!/usr/bin/env python3
from __future__ import annotations

import os
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / "third_party/wine/pocketpc-display-bridge"
MAGIC = 0x31424450
VERSION = 4
CAPABILITIES = 23


def run(argv: list[str], cwd: Path) -> None:
    subprocess.run(
        argv,
        cwd=cwd,
        check=True,
    )


def read_exact(
    connection: socket.socket,
    size: int,
) -> bytes:
    result = bytearray()
    while len(result) < size:
        block = connection.recv(
            size - len(result),
        )
        if not block:
            raise RuntimeError(
                "DISPLAY_BRIDGE_NATIVE_EOF"
            )
        result.extend(block)
    return bytes(result)


def read_frame(
    connection: socket.socket,
) -> tuple[int, int, bytes]:
    header = read_exact(
        connection,
        20,
    )
    magic, version, msg_type, size, sequence = (
        struct.unpack(
            "<IHHIQ",
            header,
        )
    )
    if (
        magic != MAGIC
        or version != VERSION
        or size > 1024 * 1024
    ):
        raise RuntimeError(
            "DISPLAY_BRIDGE_NATIVE_HEADER_INVALID"
        )
    return (
        msg_type,
        sequence,
        read_exact(
            connection,
            size,
        ),
    )


def write_frame(
    connection: socket.socket,
    msg_type: int,
    sequence: int,
    payload: bytes = b"",
) -> None:
    connection.sendall(
        struct.pack(
            "<IHHIQ",
            MAGIC,
            VERSION,
            msg_type,
            len(payload),
            sequence,
        ) +
        payload
    )


def validate_pixels(
    path: Path,
) -> None:
    data = path.read_bytes()
    if len(data) != 64 * 64 * 4:
        raise RuntimeError(
            "DISPLAY_BRIDGE_NATIVE_FRAME_SIZE_INVALID"
        )

    for y in range(64):
        for x in range(64):
            offset = (
                y * 256 +
                x * 4
            )
            expected = bytes(
                    (
                        x & 0xff,
                        y & 0xff,
                        (x ^ y) & 0xff,
                        0xff,
                    ),
                )
            if (
                data[
                    offset:
                    offset + 4
                ] != expected
            ):
                raise RuntimeError(
                    "DISPLAY_BRIDGE_NATIVE_PIXEL_MISMATCH:" +
                    f"{x},{y}"
                )


def main() -> int:
    compiler = shutil.which("cc")
    if not compiler:
        raise SystemExit(
            "DISPLAY_BRIDGE_NATIVE_CC_MISSING"
        )

    token = bytes(range(32))
    identity = b"a" * 64
    surface_token = bytes.fromhex(
            "00112233445566778899aabbccddeeff"
        )
    socket_name = (
        "pocketpc.display.native." +
        os.urandom(6).hex()
    )
    surface_path = Path(
            "/tmp/.pocketpc-surface-" +
            surface_token.hex() +
            ".bgra"
        )

    with tempfile.TemporaryDirectory(
        prefix="pocketpc-bridge-native-",
    ) as temporary:
        work = Path(temporary)
        executable = (
            work /
            "display_bridge_smoke"
        )
        window_map_executable = (
            work /
            "window_map_smoke"
        )
        window_bridge_executable = (
            work /
            "window_bridge_smoke"
        )
        failstop_executable = (
            work /
            "transport_failstop_smoke"
        )
        surface_writer_executable = (
            work /
            "surface_writer_smoke"
        )
        surface_visibility_executable = (
            work /
            "surface_writer_visibility_smoke"
        )

        run(
            [
                compiler,
                "-std=c11",
                "-Wall",
                "-Wextra",
                "-Werror",
                "-Wpedantic",
                "-O2",
                "-I",
                str(BRIDGE),
                str(
                    BRIDGE /
                    "pocketpc_display_bridge.c"
                ),
                str(
                    BRIDGE /
                    "display_bridge_smoke.c"
                ),
                "-o",
                str(executable),
            ],
            work,
        )
        run(
            [
                compiler,
                "-std=c11",
                "-Wall",
                "-Wextra",
                "-Werror",
                "-Wpedantic",
                "-O2",
                "-I",
                str(BRIDGE),
                str(
                    BRIDGE /
                    "pocketpc_wine_window_map.c"
                ),
                str(
                    BRIDGE /
                    "window_map_smoke.c"
                ),
                "-o",
                str(window_map_executable),
            ],
            work,
        )
        window_map_result = subprocess.run(
                [
                    str(
                        window_map_executable,
                    )
                ],
                cwd=work,
                check=True,
                capture_output=True,
                text=True,
            )
        if (
            "POCKETPC_WINE_WINDOW_MAP_OK"
            not in window_map_result.stdout
        ):
            raise RuntimeError(
                "WINE_WINDOW_MAP_MARKER_MISSING"
            )

        run(
            [
                compiler,
                "-std=c11",
                "-Wall",
                "-Wextra",
                "-Werror",
                "-Wpedantic",
                "-O2",
                "-I",
                str(BRIDGE),
                str(
                    BRIDGE /
                    "pocketpc_display_bridge.c"
                ),
                str(
                    BRIDGE /
                    "pocketpc_wine_window_map.c"
                ),
                str(
                    BRIDGE /
                    "pocketpc_wine_window_bridge.c"
                ),
                str(
                    BRIDGE /
                    "window_bridge_smoke.c"
                ),
                "-o",
                str(window_bridge_executable),
            ],
            work,
        )
        window_bridge_result = subprocess.run(
                [
                    str(
                        window_bridge_executable,
                    )
                ],
                cwd=work,
                check=True,
                capture_output=True,
                text=True,
            )
        if (
            "POCKETPC_WINE_WINDOW_BRIDGE_OK"
            not in window_bridge_result.stdout
        ):
            raise RuntimeError(
                "WINE_WINDOW_BRIDGE_MARKER_MISSING"
            )

        run(
            [
                compiler,
                "-std=c11",
                "-Wall",
                "-Wextra",
                "-Werror",
                "-Wpedantic",
                "-O2",
                "-I",
                str(BRIDGE),
                str(
                    BRIDGE /
                    "pocketpc_display_bridge.c"
                ),
                str(
                    BRIDGE /
                    "transport_failstop_smoke.c"
                ),
                "-o",
                str(failstop_executable),
            ],
            work,
        )
        failstop_result = subprocess.run(
                [
                    str(
                        failstop_executable,
                    )
                ],
                cwd=work,
                check=True,
                capture_output=True,
                text=True,
            )
        if (
            "POCKETPC_DISPLAY_BRIDGE_FAILSTOP_OK"
            not in failstop_result.stdout
        ):
            raise RuntimeError(
                "DISPLAY_BRIDGE_FAILSTOP_MARKER_MISSING"
            )

        run(
            [
                compiler,
                "-std=c11",
                "-Wall",
                "-Wextra",
                "-Werror",
                "-Wpedantic",
                "-O2",
                "-I",
                str(BRIDGE),
                str(
                    BRIDGE /
                    "pocketpc_display_bridge.c"
                ),
                str(
                    BRIDGE /
                    "pocketpc_surface_writer.c"
                ),
                str(
                    BRIDGE /
                    "surface_writer_smoke.c"
                ),
                "-o",
                str(surface_writer_executable),
            ],
            work,
        )
        surface_writer_result = subprocess.run(
                [
                    str(
                        surface_writer_executable,
                    )
                ],
                cwd=work,
                check=True,
                capture_output=True,
                text=True,
            )
        if (
            "POCKETPC_SURFACE_WRITER_SMOKE_OK"
            not in
            surface_writer_result.stdout
        ):
            raise RuntimeError(
                "SURFACE_WRITER_MARKER_MISSING"
            )

        run(
            [
                compiler,
                "-std=c11",
                "-Wall",
                "-Wextra",
                "-Werror",
                "-Wpedantic",
                "-O2",
                "-I",
                str(BRIDGE),
                str(
                    BRIDGE /
                    "pocketpc_display_bridge.c"
                ),
                str(
                    BRIDGE /
                    "pocketpc_surface_writer.c"
                ),
                str(
                    BRIDGE /
                    "surface_writer_visibility_smoke.c"
                ),
                "-o",
                str(
                    surface_visibility_executable
                ),
            ],
            work,
        )
        surface_visibility_result = subprocess.run(
                [
                    str(
                        surface_visibility_executable
                    )
                ],
                cwd=work,
                check=True,
                capture_output=True,
                text=True,
            )
        if (
            "POCKETPC_SURFACE_VISIBILITY_SMOKE_OK"
            not in
            surface_visibility_result.stdout
        ):
            raise RuntimeError(
                "SURFACE_VISIBILITY_MARKER_MISSING"
            )


        server = socket.socket(
                socket.AF_UNIX,
                socket.SOCK_STREAM,
            )
        process:
            subprocess.Popen[str] |
            None = None

        try:
            server.bind(
                "\0" +
                socket_name,
            )
            server.listen(1)

            surface_path.write_bytes(
                bytes(
                    64 * 64 * 4
                ),
            )

            environment = (
                os.environ.copy()
            )
            environment.update(
                {
                    "POCKETPC_DISPLAY_PROTOCOL":
                        "4",
                    "POCKETPC_DISPLAY_SOCKET":
                        socket_name,
                    "POCKETPC_DISPLAY_TOKEN":
                        token.hex(),
                    "POCKETPC_DISPLAY_RUNTIME_SHA256":
                        identity.decode(
                            "ascii"
                        ),
                    "POCKETPC_DISPLAY_HOST_CAPS":
                        str(
                            CAPABILITIES
                        ),
                },
            )

            process = subprocess.Popen(
                    [
                        str(executable),
                    ],
                    cwd=work,
                    env=environment,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                )

            connection, _ = server.accept()
            with connection:
                msg_type, sequence, payload = (
                    read_frame(
                        connection,
                    )
                )
                assert (
                    msg_type,
                    sequence,
                ) == (1, 0)
                assert payload[:32] == token
                assert (
                    payload[
                        32:96
                    ] ==
                    identity
                )
                assert (
                    struct.unpack(
                        "<I",
                        payload[96:],
                    )[0] ==
                    CAPABILITIES
                )

                write_frame(
                    connection,
                    2,
                    0,
                    struct.pack(
                        "<I",
                        CAPABILITIES,
                    ),
                )

                msg_type, sequence, payload = (
                    read_frame(
                        connection,
                    )
                )
                assert (
                    msg_type,
                    sequence,
                ) == (10, 1)
                assert (
                    struct.unpack(
                        "<QQIii",
                        payload,
                    ) ==
                    (
                        1,
                        0,
                        0,
                        640,
                        360,
                    )
                )

                msg_type, sequence, payload = (
                    read_frame(
                        connection,
                    )
                )
                assert (
                    msg_type,
                    sequence,
                ) == (19, 2)
                assert (
                    struct.unpack(
                        "<QQiiII",
                        payload,
                    ) ==
                    (
                        1,
                        1,
                        64,
                        64,
                        1,
                        0,
                    )
                )

                surface = struct.pack(
                        "<QQQiiiI16s",
                        1,
                        1,
                        1,
                        64,
                        64,
                        256,
                        1,
                        surface_token,
                    )
                assert len(surface) == 56
                write_frame(
                    connection,
                    20,
                    1,
                    surface,
                )

                msg_type, sequence, payload = (
                    read_frame(
                        connection,
                    )
                )
                assert (
                    msg_type,
                    sequence,
                ) == (11, 3)
                geometry = struct.unpack(
                        "<QiiiiIIQ",
                        payload,
                    )
                assert (
                    geometry ==
                    (
                        1,
                        20,
                        30,
                        640,
                        360,
                        1,
                        1,
                        0,
                    )
                )

                msg_type, sequence, payload = (
                    read_frame(
                        connection,
                    )
                )
                assert (
                    msg_type,
                    sequence,
                ) == (21, 4)
                assert (
                    struct.unpack(
                        "<QQQQ",
                        payload,
                    ) ==
                    (1, 1, 1, 1)
                )

                validate_pixels(
                    surface_path,
                )

                write_frame(
                    connection,
                    30,
                    2,
                    struct.pack(
                        "<Qiiiiii",
                        1,
                        1,
                        100,
                        80,
                        1,
                        0,
                        0,
                    ),
                )
                write_frame(
                    connection,
                    31,
                    3,
                    struct.pack(
                        "<QIIIII",
                        1,
                        1,
                        65,
                        30,
                        0,
                        0,
                    ),
                )
                write_frame(
                    connection,
                    40,
                    4,
                    struct.pack(
                        "<QQQQI",
                        1,
                        1,
                        1,
                        1,
                        0,
                    ),
                )

                write_frame(
                    connection,
                    13,
                    5,
                    struct.pack(
                        "<QII",
                        1,
                        5,
                        0,
                    ),
                )

                msg_type, sequence, payload = (
                    read_frame(
                        connection,
                    )
                )
                assert (
                    msg_type,
                    sequence,
                ) == (12, 5)
                assert (
                    struct.unpack(
                        "<Q",
                        payload,
                    )[0] ==
                    1
                )

            stdout, stderr = process.communicate(
                    timeout=5,
                )
            if process.returncode != 0:
                raise RuntimeError(
                    "DISPLAY_BRIDGE_NATIVE_PROCESS_FAILED:" +
                    str(
                        process.returncode
                    ) +
                    "\n" +
                    stderr
                )

            markers = (
                "POCKETPC_DISPLAY_BRIDGE_WINDOW_OK",
                "POCKETPC_DISPLAY_BRIDGE_FRAME_WRITTEN_OK",
                "POCKETPC_DISPLAY_BRIDGE_POINTER_OK",
                "POCKETPC_DISPLAY_BRIDGE_KEY_OK",
                "POCKETPC_DISPLAY_BRIDGE_FRAME_ACK_OK",
                "POCKETPC_DISPLAY_BRIDGE_WINDOW_COMMAND_OK",
                "POCKETPC_DISPLAY_BRIDGE_SMOKE_OK",
            )
            for marker in markers:
                if marker not in stdout:
                    raise RuntimeError(
                        "DISPLAY_BRIDGE_NATIVE_MARKER_MISSING:" +
                        marker
                    )

            print(
                "DISPLAY_BRIDGE_NATIVE_INTEGRATION_OK"
            )
            print(
                "shared_framebuffer=BGRA8888"
            )
            print(
                "wine_window_map=native-software-pass"
            )
            print(
                "wine_window_bridge=native-software-pass"
            )
            print(
                "transport_failstop=native-software-pass"
            )
            print(
                "surface_writer=native-software-pass"
            )
            print(
                "surface_visibility=cross-process-native-pass"
            )
            print(
                "window_command=native-host-to-guest"
            )
            print(
                "transport=x86_64-native-host-fixture"
            )
            print(
                "android_box64_execution_evidence=false"
            )
            return 0
        finally:
            server.close()
            surface_path.unlink(
                missing_ok=True,
            )
            if (
                process is not None and
                process.poll() is None
            ):
                process.kill()
                process.wait()


if __name__ == "__main__":
    raise SystemExit(main())
