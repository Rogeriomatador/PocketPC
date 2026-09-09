#!/usr/bin/env python3
"""Build pinned Wine 11.0 x86_64 headless and emit a review-only guest package."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LOCK_PATH = ROOT / "third_party/wine/LOCK.json"
DRIVER_PREPARER = ROOT / "scripts/prepare-wine-pocketpc-driver.py"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while block := f.read(1024 * 1024):
            h.update(block)
    return h.hexdigest()


def run(argv: list[str], cwd: Path, log: Path, env: dict[str, str] | None = None) -> None:
    merged = os.environ.copy()
    if env:
        merged.update(env)
    with log.open("w", encoding="utf-8") as out:
        result = subprocess.run(
            argv,
            cwd=cwd,
            stdout=out,
            stderr=subprocess.STDOUT,
            text=True,
            env=merged,
        )
    if result.returncode:
        raise RuntimeError(
            f"command failed ({result.returncode}): {' '.join(argv)}"
        )


def elf_header(path: Path) -> tuple[int, int, int]:
    raw = path.read_bytes()[:20]
    if len(raw) < 20 or raw[:4] != b"\x7fELF":
        raise ValueError(f"{path.name} is not ELF")
    if raw[5] != 1:
        raise ValueError(f"{path.name} is not little-endian")
    elf_type, machine = struct.unpack_from("<HH", raw, 16)
    return raw[4], elf_type, machine


def pe_machine(path: Path) -> tuple[int, int]:
    raw = path.read_bytes()
    if len(raw) < 0x40 or raw[:2] != b"MZ":
        raise ValueError("Win64 smoke is not PE")
    pe_offset = struct.unpack_from("<I", raw, 0x3C)[0]
    if pe_offset + 26 > len(raw) or raw[pe_offset:pe_offset + 4] != b"PE\x00\x00":
        raise ValueError("Win64 smoke PE signature invalid")
    machine = struct.unpack_from("<H", raw, pe_offset + 4)[0]
    optional_magic = struct.unpack_from("<H", raw, pe_offset + 24)[0]
    return machine, optional_magic


def build_win64_smoke(work: Path) -> Path:
    source = work / "pocketpc-win64-smoke.c"
    output = work / "pocketpc-win64-smoke.exe"
    source.write_text(
        """#include <windows.h>

int main(void) {
    static const char message[] = "POCKETPC_WIN64_SMOKE_OK\\r\\n";
    DWORD written = 0;
    HANDLE out = GetStdHandle(STD_OUTPUT_HANDLE);
    if (out == INVALID_HANDLE_VALUE || out == NULL) return 10;
    if (!WriteFile(out, message, sizeof(message) - 1, &written, NULL)) return 11;
    return written == sizeof(message) - 1 ? 0 : 12;
}
""",
        encoding="utf-8",
    )
    run(
        [
            "x86_64-w64-mingw32-gcc",
            "-Os",
            "-s",
            "-static",
            "-Wl,--no-insert-timestamp",
            "-o",
            str(output),
            str(source),
        ],
        work,
        work / "win64-smoke-build.log",
    )
    machine, magic = pe_machine(output)
    if machine != 0x8664 or magic != 0x20B:
        raise SystemExit(
            "WIN64_SMOKE_PE_TARGET_MISMATCH "
            f"machine=0x{machine:04x} optional=0x{magic:04x}"
        )
    return output


def build_pocketpc_window_smoke(work: Path) -> Path:
    source = work / "pocketpc-window-smoke.c"
    output = work / "pocketpc-window-smoke.exe"
    source.write_text(
        """#include <windows.h>
#include <stdio.h>

static int got_pointer = 0;
static int got_key = 0;

static void maybe_finish(HWND hwnd) {
    if (got_pointer && got_key) {
        printf("POCKETPC_WINE_DRIVER_INPUT_OK\\n");
        fflush(stdout);
        DestroyWindow(hwnd);
    }
}

static LRESULT CALLBACK wndproc(
    HWND hwnd,
    UINT message,
    WPARAM wparam,
    LPARAM lparam
) {
    (void)lparam;

    switch (message) {
    case WM_PAINT: {
        PAINTSTRUCT paint;
        RECT rect;
        RECT left;
        RECT right;
        HBRUSH blue;
        HBRUSH orange;
        HDC dc = BeginPaint(hwnd, &paint);

        GetClientRect(hwnd, &rect);
        left = rect;
        right = rect;
        left.right = rect.left + (rect.right - rect.left) / 2;
        right.left = left.right;

        blue = CreateSolidBrush(RGB(24, 96, 210));
        orange = CreateSolidBrush(RGB(224, 92, 28));
        FillRect(dc, &left, blue);
        FillRect(dc, &right, orange);
        DeleteObject(blue);
        DeleteObject(orange);
        EndPaint(hwnd, &paint);

        printf("POCKETPC_WINE_DRIVER_PAINT_OK\\n");
        fflush(stdout);
        return 0;
    }

    case WM_LBUTTONDOWN:
        got_pointer = 1;
        printf("POCKETPC_WINE_DRIVER_POINTER_OK\\n");
        fflush(stdout);
        maybe_finish(hwnd);
        return 0;

    case WM_KEYDOWN:
        if (wparam == 'A') {
            got_key = 1;
            printf("POCKETPC_WINE_DRIVER_KEY_OK\\n");
            fflush(stdout);
            maybe_finish(hwnd);
            return 0;
        }
        break;

    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;
    }

    return DefWindowProcW(
        hwnd,
        message,
        wparam,
        lparam
    );
}

int main(void) {
    HINSTANCE instance =
        GetModuleHandleW(NULL);
    WNDCLASSW klass;
    HWND hwnd;
    MSG message;

    ZeroMemory(&klass, sizeof(klass));
    klass.lpfnWndProc = wndproc;
    klass.hInstance = instance;
    klass.hCursor =
        LoadCursorW(NULL, IDC_ARROW);
    klass.hbrBackground =
        (HBRUSH)(COLOR_WINDOW + 1);
    klass.lpszClassName =
        L"PocketPcWineDriverSmoke";

    if (
        !RegisterClassW(&klass) &&
        GetLastError() !=
            ERROR_CLASS_ALREADY_EXISTS
    ) {
        return 70;
    }

    hwnd =
        CreateWindowExW(
            0,
            klass.lpszClassName,
            L"PocketPC Wine Driver Smoke",
            WS_OVERLAPPEDWINDOW,
            80,
            70,
            640,
            360,
            NULL,
            NULL,
            instance,
            NULL
        );
    if (!hwnd) {
        return 71;
    }

    ShowWindow(hwnd, SW_SHOW);
    UpdateWindow(hwnd);
    SetFocus(hwnd);

    printf("POCKETPC_WINE_DRIVER_WINDOW_OK\\n");
    fflush(stdout);

    while (
        GetMessageW(
            &message,
            NULL,
            0,
            0
        ) > 0
    ) {
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }

    if (!got_pointer || !got_key) {
        printf(
            "POCKETPC_WINE_DRIVER_SMOKE_FAILED pointer=%d key=%d\\n",
            got_pointer,
            got_key
        );
        return 72;
    }

    printf("POCKETPC_WINE_DRIVER_SMOKE_OK\\n");
    fflush(stdout);
    return 0;
}
""",
        encoding="utf-8",
    )
    run(
        [
            "x86_64-w64-mingw32-gcc",
            "-Os",
            "-s",
            "-Wl,--no-insert-timestamp",
            "-o",
            str(output),
            str(source),
            "-lgdi32",
            "-luser32",
        ],
        work,
        work / "pocketpc-window-smoke-build.log",
    )
    machine, magic = pe_machine(output)
    if machine != 0x8664 or magic != 0x20B:
        raise SystemExit(
            "POCKETPC_WINDOW_SMOKE_PE_TARGET_MISMATCH"
        )
    return output


def build_d3d11_smoke(work: Path) -> Path:
    source = work / "pocketpc-d3d11-smoke.c"
    output = work / "pocketpc-d3d11-smoke.exe"
    source.write_text(
        """#define COBJMACROS
#include <windows.h>
#include <d3d11.h>
#include <stdio.h>

int main(void) {
    D3D_FEATURE_LEVEL requested[] = { D3D_FEATURE_LEVEL_11_0 };
    D3D_FEATURE_LEVEL obtained = 0;
    ID3D11Device *device = NULL;
    ID3D11DeviceContext *context = NULL;
    HRESULT hr = D3D11CreateDevice(
        NULL,
        D3D_DRIVER_TYPE_HARDWARE,
        NULL,
        0,
        requested,
        1,
        D3D11_SDK_VERSION,
        &device,
        &obtained,
        &context
    );
    if (FAILED(hr)) {
        printf("POCKETPC_D3D11_SMOKE_FAILED hr=0x%08lx\\n", (unsigned long)hr);
        return 20;
    }
    printf("POCKETPC_D3D11_SMOKE_OK feature=0x%x\\n", (unsigned)obtained);
    if (context) ID3D11DeviceContext_Release(context);
    if (device) ID3D11Device_Release(device);
    return 0;
}
""",
        encoding="utf-8",
    )
    run(
        [
            "x86_64-w64-mingw32-gcc",
            "-Os",
            "-s",
            "-Wl,--no-insert-timestamp",
            "-o",
            str(output),
            str(source),
            "-ld3d11",
            "-ldxgi",
        ],
        work,
        work / "d3d11-smoke-build.log",
    )
    machine, magic = pe_machine(output)
    if machine != 0x8664 or magic != 0x20B:
        raise SystemExit(
            "D3D11_SMOKE_PE_TARGET_MISMATCH "
            f"machine=0x{machine:04x} optional=0x{magic:04x}"
        )
    return output


def build_d3d11_present_smoke(work: Path) -> Path:
    source = work / "pocketpc-d3d11-present-smoke.c"
    output = work / "pocketpc-d3d11-present-smoke.exe"
    source.write_text(
        """#define COBJMACROS
#include <windows.h>
#include <d3d11.h>
#include <dxgi.h>
#include <stdio.h>

static LRESULT CALLBACK wndproc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    return DefWindowProcW(hwnd, msg, wp, lp);
}

int main(void) {
    HINSTANCE instance = GetModuleHandleW(NULL);
    WNDCLASSW wc;
    ZeroMemory(&wc, sizeof(wc));
    wc.lpfnWndProc = wndproc;
    wc.hInstance = instance;
    wc.lpszClassName = L"PocketPcD3D11PresentSmoke";
    if (!RegisterClassW(&wc) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) return 70;

    HWND window = CreateWindowExW(
        0,
        wc.lpszClassName,
        L"PocketPC",
        WS_OVERLAPPEDWINDOW,
        CW_USEDEFAULT,
        CW_USEDEFAULT,
        96,
        96,
        NULL,
        NULL,
        instance,
        NULL
    );
    if (!window) {
        printf("POCKETPC_D3D11_PRESENT_WINDOW_FAILED error=%lu\\n", (unsigned long)GetLastError());
        return 71;
    }

    DXGI_SWAP_CHAIN_DESC desc;
    ZeroMemory(&desc, sizeof(desc));
    desc.BufferDesc.Width = 64;
    desc.BufferDesc.Height = 64;
    desc.BufferDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    desc.SampleDesc.Count = 1;
    desc.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    desc.BufferCount = 2;
    desc.OutputWindow = window;
    desc.Windowed = TRUE;
    desc.SwapEffect = DXGI_SWAP_EFFECT_DISCARD;

    IDXGISwapChain *swap = NULL;
    ID3D11Device *device = NULL;
    ID3D11DeviceContext *context = NULL;
    D3D_FEATURE_LEVEL feature = 0;
    HRESULT hr = D3D11CreateDeviceAndSwapChain(
        NULL,
        D3D_DRIVER_TYPE_HARDWARE,
        NULL,
        0,
        NULL,
        0,
        D3D11_SDK_VERSION,
        &desc,
        &swap,
        &device,
        &feature,
        &context
    );
    if (FAILED(hr)) {
        DestroyWindow(window);
        printf("POCKETPC_D3D11_PRESENT_CREATE_FAILED hr=0x%08lx\\n", (unsigned long)hr);
        return 72;
    }

    hr = IDXGISwapChain_Present(swap, 0, 0);
    if (FAILED(hr)) {
        ID3D11DeviceContext_Release(context);
        ID3D11Device_Release(device);
        IDXGISwapChain_Release(swap);
        DestroyWindow(window);
        printf("POCKETPC_D3D11_PRESENT_FAILED hr=0x%08lx\\n", (unsigned long)hr);
        return 73;
    }

    printf("POCKETPC_D3D11_PRESENT_SMOKE_OK feature=0x%x\\n", (unsigned)feature);
    ID3D11DeviceContext_Release(context);
    ID3D11Device_Release(device);
    IDXGISwapChain_Release(swap);
    DestroyWindow(window);
    return 0;
}
""",
        encoding="utf-8",
    )
    run(
        [
            "x86_64-w64-mingw32-gcc",
            "-Os",
            "-s",
            "-Wl,--no-insert-timestamp",
            "-o",
            str(output),
            str(source),
            "-ld3d11",
            "-ldxgi",
            "-luser32",
        ],
        work,
        work / "d3d11-present-smoke-build.log",
    )
    machine, magic = pe_machine(output)
    if machine != 0x8664 or magic != 0x20B:
        raise SystemExit("D3D11_PRESENT_SMOKE_PE_TARGET_MISMATCH")
    return output


def build_windows_process_smoke(work: Path) -> Path:
    source = work / "pocketpc-process-ipc-smoke.c"
    output = work / "pocketpc-process-ipc-smoke.exe"
    source.write_text(
        """#include <windows.h>
#include <stdio.h>
#include <string.h>

int main(int argc, char **argv) {
    static const char child_message[] = "POCKETPC_WIN_PROCESS_CHILD_OK\\r\\n";
    if (argc > 1 && strcmp(argv[1], "--child") == 0) {
        DWORD written = 0;
        HANDLE out = GetStdHandle(STD_OUTPUT_HANDLE);
        if (!WriteFile(out, child_message, sizeof(child_message) - 1, &written, NULL)) return 31;
        return 37;
    }

    SECURITY_ATTRIBUTES security = { sizeof(security), NULL, TRUE };
    HANDLE read_pipe = NULL;
    HANDLE write_pipe = NULL;
    if (!CreatePipe(&read_pipe, &write_pipe, &security, 0)) return 32;
    if (!SetHandleInformation(read_pipe, HANDLE_FLAG_INHERIT, 0)) return 33;

    WCHAR module[MAX_PATH];
    if (!GetModuleFileNameW(NULL, module, MAX_PATH)) return 34;

    WCHAR command[MAX_PATH + 32];
    if (swprintf(command, MAX_PATH + 32, L"\\"%ls\\" --child", module) < 0) return 35;

    STARTUPINFOW startup;
    PROCESS_INFORMATION process;
    ZeroMemory(&startup, sizeof(startup));
    ZeroMemory(&process, sizeof(process));
    startup.cb = sizeof(startup);
    startup.dwFlags = STARTF_USESTDHANDLES;
    startup.hStdOutput = write_pipe;
    startup.hStdError = write_pipe;
    startup.hStdInput = GetStdHandle(STD_INPUT_HANDLE);

    if (!CreateProcessW(NULL, command, NULL, NULL, TRUE, CREATE_NO_WINDOW, NULL, NULL, &startup, &process)) return 36;
    CloseHandle(write_pipe);
    write_pipe = NULL;

    char buffer[256] = {0};
    DWORD read = 0;
    ReadFile(read_pipe, buffer, sizeof(buffer) - 1, &read, NULL);
    WaitForSingleObject(process.hProcess, 10000);

    DWORD exit_code = 0;
    GetExitCodeProcess(process.hProcess, &exit_code);
    CloseHandle(process.hThread);
    CloseHandle(process.hProcess);
    CloseHandle(read_pipe);

    if (exit_code != 37) return 38;
    if (strstr(buffer, "POCKETPC_WIN_PROCESS_CHILD_OK") == NULL) return 39;
    printf("POCKETPC_WIN_PROCESS_IPC_SMOKE_OK\\n");
    return 0;
}
""",
        encoding="utf-8",
    )
    run(
        [
            "x86_64-w64-mingw32-gcc",
            "-Os",
            "-s",
            "-Wl,--no-insert-timestamp",
            "-o",
            str(output),
            str(source),
        ],
        work,
        work / "process-ipc-smoke-build.log",
    )
    machine, magic = pe_machine(output)
    if machine != 0x8664 or magic != 0x20B:
        raise SystemExit("WINDOWS_PROCESS_SMOKE_PE_TARGET_MISMATCH")
    return output


def build_winsock_smoke(work: Path) -> Path:
    source = work / "pocketpc-winsock-smoke.c"
    output = work / "pocketpc-winsock-smoke.exe"
    source.write_text(
        """#include <winsock2.h>
#include <ws2tcpip.h>
#include <stdio.h>

int main(void) {
    WSADATA data;
    if (WSAStartup(MAKEWORD(2, 2), &data) != 0) return 40;

    SOCKET sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock == INVALID_SOCKET) {
        WSACleanup();
        return 41;
    }

    struct addrinfo hints = {0};
    struct addrinfo *result = NULL;
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    int rc = getaddrinfo("localhost", "80", &hints, &result);
    if (rc != 0 || result == NULL) {
        closesocket(sock);
        WSACleanup();
        return 42;
    }

    freeaddrinfo(result);
    closesocket(sock);
    WSACleanup();
    printf("POCKETPC_WINSOCK_SMOKE_OK\\n");
    return 0;
}
""",
        encoding="utf-8",
    )
    run(
        [
            "x86_64-w64-mingw32-gcc",
            "-Os",
            "-s",
            "-Wl,--no-insert-timestamp",
            "-o",
            str(output),
            str(source),
            "-lws2_32",
        ],
        work,
        work / "winsock-smoke-build.log",
    )
    machine, magic = pe_machine(output)
    if machine != 0x8664 or magic != 0x20B:
        raise SystemExit("WINSOCK_SMOKE_PE_TARGET_MISMATCH")
    return output


def build_winmm_audio_api_smoke(work: Path) -> Path:
    source = work / "pocketpc-winmm-audio-api-smoke.c"
    output = work / "pocketpc-winmm-audio-api-smoke.exe"
    source.write_text(
        """#include <windows.h>
#include <mmsystem.h>
#include <stdio.h>

int main(void) {
    UINT count = waveOutGetNumDevs();
    if (count > 0) {
        WAVEOUTCAPSW caps;
        MMRESULT rc = waveOutGetDevCapsW(0, &caps, sizeof(caps));
        if (rc != MMSYSERR_NOERROR) return 50;
    }
    printf("POCKETPC_WINMM_AUDIO_API_OK devices=%u\\n", (unsigned)count);
    return 0;
}
""",
        encoding="utf-8",
    )
    run(
        [
            "x86_64-w64-mingw32-gcc",
            "-Os",
            "-s",
            "-Wl,--no-insert-timestamp",
            "-o",
            str(output),
            str(source),
            "-lwinmm",
        ],
        work,
        work / "winmm-audio-api-smoke-build.log",
    )
    machine, magic = pe_machine(output)
    if machine != 0x8664 or magic != 0x20B:
        raise SystemExit("WINMM_AUDIO_SMOKE_PE_TARGET_MISMATCH")
    return output


def build_raw_input_api_smoke(work: Path) -> Path:
    source = work / "pocketpc-raw-input-api-smoke.c"
    output = work / "pocketpc-raw-input-api-smoke.exe"
    source.write_text(
        """#include <windows.h>
#include <stdio.h>

int main(void) {
    UINT count = 0;
    UINT rc = GetRawInputDeviceList(NULL, &count, sizeof(RAWINPUTDEVICELIST));
    if (rc == (UINT)-1) return 60;
    printf("POCKETPC_RAW_INPUT_API_OK devices=%u\\n", (unsigned)count);
    return 0;
}
""",
        encoding="utf-8",
    )
    run(
        [
            "x86_64-w64-mingw32-gcc",
            "-Os",
            "-s",
            "-Wl,--no-insert-timestamp",
            "-o",
            str(output),
            str(source),
            "-luser32",
        ],
        work,
        work / "raw-input-api-smoke-build.log",
    )
    machine, magic = pe_machine(output)
    if machine != 0x8664 or magic != 0x20B:
        raise SystemExit("RAW_INPUT_SMOKE_PE_TARGET_MISMATCH")
    return output


def flatten_install_tree(source_root: Path, package_root: Path) -> list[dict[str, object]]:
    records: list[dict[str, object]] = []
    for source in sorted(source_root.rglob("*"), key=lambda p: p.as_posix()):
        relative = source.relative_to(source_root)
        if source.is_dir() and not source.is_symlink():
            continue
        if source.is_symlink():
            target_text = os.readlink(source)
            if target_text.startswith("/opt/pocketpc/wine/"):
                resolved = (
                    source_root /
                    target_text.removeprefix("/opt/pocketpc/wine/")
                ).resolve()
            else:
                resolved = (source.parent / target_text).resolve()
        else:
            resolved = source.resolve()
        try:
            resolved.relative_to(source_root.resolve())
        except ValueError as error:
            raise SystemExit(f"WINE_INSTALL_SYMLINK_ESCAPES:{relative}") from error
        if not resolved.is_file():
            raise SystemExit(f"WINE_INSTALL_UNSUPPORTED_ENTRY:{relative}")

        destination = package_root / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(resolved, destination)
        mode = resolved.stat().st_mode
        executable = bool(mode & 0o111)
        destination.chmod(0o755 if executable else 0o644)
        records.append(
            {
                "path": relative.as_posix(),
                "bytes": destination.stat().st_size,
                "sha256": sha256(destination),
                "executable": executable,
            }
        )
    return records


def deterministic_zip(zip_path: Path, package_root: Path, records: list[dict[str, object]]) -> None:
    with zipfile.ZipFile(
        zip_path,
        "w",
        compression=zipfile.ZIP_DEFLATED,
        compresslevel=9,
    ) as archive:
        ordered = [
            ("guest-tool-manifest.json", False),
            *[(str(item["path"]), bool(item["executable"])) for item in records],
        ]
        for relative, executable in ordered:
            source = package_root / relative
            info = zipfile.ZipInfo(relative, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = (0o100755 if executable else 0o100644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, source.read_bytes())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", type=Path, required=True)
    args = parser.parse_args()

    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    work = args.work.resolve()
    repo = ROOT.resolve()
    if work == repo or repo in work.parents:
        raise SystemExit("WINE_WORK_MUST_BE_OUTSIDE_REPOSITORY")
    work.mkdir(parents=True, exist_ok=False)

    source = work / "source"
    build = work / "build"
    destdir = work / "destdir"
    package_root = work / "guest-package"
    package_root.mkdir()

    run(["git", "init", str(source)], work, work / "git-init.log")
    run(
        ["git", "-C", str(source), "remote", "add", "origin", lock["repository"]],
        work,
        work / "git-remote.log",
    )
    run(
        ["git", "-C", str(source), "fetch", "--depth", "1", "origin", lock["commit"]],
        work,
        work / "git-fetch.log",
    )
    run(
        ["git", "-C", str(source), "checkout", "--detach", "FETCH_HEAD"],
        work,
        work / "git-checkout.log",
    )
    actual = subprocess.check_output(
        ["git", "-C", str(source), "rev-parse", "HEAD"],
        text=True,
    ).strip()
    if actual != lock["commit"]:
        raise SystemExit("WINE_SOURCE_COMMIT_MISMATCH")

    copying = (source / "COPYING.LIB").read_text(encoding="utf-8", errors="replace")
    if "GNU LESSER GENERAL PUBLIC LICENSE" not in copying or "Version 2.1" not in copying:
        raise SystemExit("WINE_LICENSE_EVIDENCE_MISMATCH")

    driver_overlay_evidence =
        work / "wine-pocketpc-driver-overlay-evidence.json"
    run(
        [
            sys.executable,
            str(DRIVER_PREPARER),
            "--wine-source",
            str(source),
            "--evidence",
            str(driver_overlay_evidence),
        ],
        work,
        work / "wine-pocketpc-driver-overlay.log",
    )
    overlay = json.loads(
        driver_overlay_evidence.read_text(
            encoding="utf-8",
        )
    )
    if (
        overlay.get("protocolVersion") != 3
        or overlay.get("driverName") != "winepocketpc.drv"
        or overlay.get("surfaceCallbackImplemented") is not True
        or overlay.get("inputInjectionImplemented") is not True
    ):
        raise SystemExit(
            "WINE_POCKETPC_DRIVER_OVERLAY_EVIDENCE_INVALID"
        )

    build.mkdir()
    common_env = {
        "LC_ALL": "C",
        "LANG": "C",
        "SOURCE_DATE_EPOCH": "0",
    }
    configure = [
        str(source / "configure"),
        "--prefix=/opt/pocketpc/wine",
        "--enable-win64",
        "--disable-tests",
        "--with-mingw",
        "--without-x",
        "--without-wayland",
        "--without-alsa",
        "--without-pulse",
        "--without-dbus",
        "--without-cups",
        "--without-fontconfig",
        "--without-freetype",
        "--without-gphoto",
        "--without-gnutls",
        "--without-gstreamer",
        "--without-oss",
        "--without-pcap",
        "--without-sane",
        "--without-usb",
        "--without-v4l2",
        "--without-opencl",
        "--without-opengl",
    ]
    run(configure, build, work / "configure.log", common_env)
    run(["make", "-j2"], build, work / "make.log", common_env)
    destdir.mkdir()
    run(
        ["make", "install", "DESTDIR=" + str(destdir)],
        build,
        work / "make-install.log",
        common_env,
    )

    installed_root = destdir / "opt/pocketpc/wine"
    wine = installed_root / "bin/wine"
    if not wine.is_file():
        raise SystemExit("WINE_ENTRYPOINT_MISSING")
    elf_class, elf_type, machine = elf_header(wine)
    if elf_class != 2 or machine != 62 or elf_type not in (2, 3):
        raise SystemExit(
            "WINE_ELF_TARGET_MISMATCH "
            f"class={elf_class} type={elf_type} machine={machine}"
        )

    pocketpc_pe_candidates =
        sorted(
            installed_root.rglob(
                "winepocketpc.drv"
            ),
            key=lambda item: item.as_posix(),
        )
    pocketpc_unix_candidates =
        sorted(
            installed_root.rglob(
                "winepocketpc.so"
            ),
            key=lambda item: item.as_posix(),
        )
    if (
        len(pocketpc_pe_candidates) != 1
        or len(pocketpc_unix_candidates) != 1
    ):
        raise SystemExit(
            "WINE_POCKETPC_DRIVER_INSTALL_MISSING:"
            f"pe={len(pocketpc_pe_candidates)}:"
            f"unix={len(pocketpc_unix_candidates)}"
        )

    pocketpc_pe =
        pocketpc_pe_candidates[0]
    pocketpc_unix =
        pocketpc_unix_candidates[0]

    if pocketpc_pe.read_bytes()[:2] != b"MZ":
        raise SystemExit(
            "WINE_POCKETPC_DRIVER_PE_INVALID"
        )
    (
        driver_elf_class,
        driver_elf_type,
        driver_machine,
    ) = elf_header(
        pocketpc_unix
    )
    if (
        driver_elf_class != 2
        or driver_machine != 62
        or driver_elf_type not in (2, 3)
    ):
        raise SystemExit(
            "WINE_POCKETPC_DRIVER_UNIXLIB_INVALID:"
            f"class={driver_elf_class}:"
            f"type={driver_elf_type}:"
            f"machine={driver_machine}"
        )

    records = flatten_install_tree(installed_root, package_root)
    smoke = build_win64_smoke(work)
    window_smoke = build_pocketpc_window_smoke(work)
    d3d11 = build_d3d11_smoke(work)
    present_smoke = build_d3d11_present_smoke(work)
    process_smoke = build_windows_process_smoke(work)
    winsock_smoke = build_winsock_smoke(work)
    audio_smoke = build_winmm_audio_api_smoke(work)
    input_smoke = build_raw_input_api_smoke(work)
    smoke_relative = Path("share/tests/pocketpc-win64-smoke.exe")
    smoke_destination = package_root / smoke_relative
    smoke_destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(smoke, smoke_destination)
    smoke_destination.chmod(0o644)
    records.append(
        {
            "path": smoke_relative.as_posix(),
            "bytes": smoke_destination.stat().st_size,
            "sha256": sha256(smoke_destination),
            "executable": False,
        }
    )

    pocketpc_window_relative =
        Path("share/tests/pocketpc-window-smoke.exe")
    pocketpc_window_destination =
        package_root / pocketpc_window_relative
    pocketpc_window_destination.parent.mkdir(
        parents=True,
        exist_ok=True,
    )
    shutil.copyfile(
        window_smoke,
        pocketpc_window_destination,
    )
    pocketpc_window_destination.chmod(0o644)
    records.append(
        {
            "path": pocketpc_window_relative.as_posix(),
            "bytes": pocketpc_window_destination.stat().st_size,
            "sha256": sha256(pocketpc_window_destination),
            "executable": False,
        }
    )

    d3d11_relative = Path("share/tests/pocketpc-d3d11-smoke.exe")
    d3d11_destination = package_root / d3d11_relative
    d3d11_destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(d3d11, d3d11_destination)
    d3d11_destination.chmod(0o644)
    records.append(
        {
            "path": d3d11_relative.as_posix(),
            "bytes": d3d11_destination.stat().st_size,
            "sha256": sha256(d3d11_destination),
            "executable": False,
        }
    )

    present_relative = Path("share/tests/pocketpc-d3d11-present-smoke.exe")
    present_destination = package_root / present_relative
    present_destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(present_smoke, present_destination)
    present_destination.chmod(0o644)
    records.append(
        {
            "path": present_relative.as_posix(),
            "bytes": present_destination.stat().st_size,
            "sha256": sha256(present_destination),
            "executable": False,
        }
    )

    extra_smokes = [
        (
            Path("share/tests/pocketpc-process-ipc-smoke.exe"),
            process_smoke,
        ),
        (
            Path("share/tests/pocketpc-winsock-smoke.exe"),
            winsock_smoke,
        ),
        (
            Path("share/tests/pocketpc-winmm-audio-api-smoke.exe"),
            audio_smoke,
        ),
        (
            Path("share/tests/pocketpc-raw-input-api-smoke.exe"),
            input_smoke,
        ),
    ]
    for relative, source_smoke in extra_smokes:
        destination = package_root / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source_smoke, destination)
        destination.chmod(0o644)
        records.append(
            {
                "path": relative.as_posix(),
                "bytes": destination.stat().st_size,
                "sha256": sha256(destination),
                "executable": False,
            }
        )

    records.sort(key=lambda item: str(item["path"]))

    guest_manifest = {
        "schemaVersion": 1,
        "id": "wine",
        "version": lock["version"],
        "architecture": "x86_64",
        "executionMode": "box64-x86_64",
        "guestRoot": "/opt/pocketpc/wine",
        "entrypoint": "bin/wine",
        "sourceCommit": actual,
        "license": lock["license"],
        "files": records,
    }
    manifest_path = package_root / "guest-tool-manifest.json"
    manifest_path.write_text(
        json.dumps(guest_manifest, indent=2) + "\n",
        encoding="utf-8",
    )

    zip_path = work / "guest-package.zip"
    deterministic_zip(zip_path, package_root, records)

    evidence = {
        "schemaVersion": 1,
        "status": "WINE_X86_64_WITH_POCKETPC_DRIVER_COMPILED_PACKAGE_NOT_GUEST_TESTED_NOT_APPROVED",
        "version": lock["version"],
        "sourceCommit": actual,
        "sourceLockSha256": sha256(LOCK_PATH),
        "entrypoint": {
            "path": "bin/wine",
            "sha256": sha256(package_root / "bin/wine"),
            "machine": machine,
        },
        "pocketPcDriver": {
            "protocolVersion": 3,
            "pePath": pocketpc_pe.relative_to(
                installed_root
            ).as_posix(),
            "peSha256": sha256(pocketpc_pe),
            "unixPath": pocketpc_unix.relative_to(
                installed_root
            ).as_posix(),
            "unixSha256": sha256(pocketpc_unix),
            "unixMachine": driver_machine,
            "surfaceCallbackImplemented": True,
            "inputInjectionImplemented": True,
            "loaded": False,
            "runtimeTested": False,
        },
        "win64Smoke": {
            "path": smoke_relative.as_posix(),
            "sha256": sha256(smoke_destination),
            "expectedOutput": "POCKETPC_WIN64_SMOKE_OK",
        },
        "pocketPcWindowSmoke": {
            "path": pocketpc_window_relative.as_posix(),
            "sha256": sha256(pocketpc_window_destination),
            "expectedOutput": "POCKETPC_WINE_DRIVER_SMOKE_OK",
            "requiresDriver": "winepocketpc.drv",
            "requiresDisplayBridgeProtocol": 3,
        },
        "d3d11Smoke": {
            "path": d3d11_relative.as_posix(),
            "sha256": sha256(d3d11_destination),
            "expectedOutput": "POCKETPC_D3D11_SMOKE_OK",
        },
        "d3d11PresentSmoke": {
            "path": present_relative.as_posix(),
            "sha256": sha256(present_destination),
            "expectedOutput": "POCKETPC_D3D11_PRESENT_SMOKE_OK",
        },
        "windowsProcessIpcSmoke": {
            "path": "share/tests/pocketpc-process-ipc-smoke.exe",
            "expectedOutput": "POCKETPC_WIN_PROCESS_IPC_SMOKE_OK",
        },
        "winsockSmoke": {
            "path": "share/tests/pocketpc-winsock-smoke.exe",
            "expectedOutput": "POCKETPC_WINSOCK_SMOKE_OK",
        },
        "winmmAudioApiSmoke": {
            "path": "share/tests/pocketpc-winmm-audio-api-smoke.exe",
            "expectedOutput": "POCKETPC_WINMM_AUDIO_API_OK",
        },
        "rawInputApiSmoke": {
            "path": "share/tests/pocketpc-raw-input-api-smoke.exe",
            "expectedOutput": "POCKETPC_RAW_INPUT_API_OK",
        },
        "package": {
            "fileCount": len(records),
            "manifestSha256": sha256(manifest_path),
            "zipBytes": zip_path.stat().st_size,
            "zipSha256": sha256(zip_path),
        },
        "limitations": [
            "PocketPC display driver compiled but not loaded or device-tested",
            "OpenGL disabled",
            "audio disabled",
            "TLS/gnutls disabled",
        ],
        "notExecuted": [
            "winepocketpc.drv driver load",
            "PocketPC shared surface presentation through Wine",
            "PocketPC pointer/keyboard injection through Wine",
            "Wine under Box64",
            "wineboot prefix creation",
            "Win64 smoke executable",
            "PocketPC Wine driver window/surface/input smoke",
            "DXVK D3D11 device smoke",
            "DXVK D3D11 presentation smoke",
            "vkd3d D3D12 smoke",
            "Windows process/IPC smoke",
            "Winsock smoke",
            "WinMM audio API smoke",
            "Raw Input API smoke",
            "Roblox",
        ],
    }
    (work / "wine-build-evidence.json").write_text(
        json.dumps(evidence, indent=2) + "\n",
        encoding="utf-8",
    )
    print("WINE_X86_64_WITH_POCKETPC_DRIVER_PACKAGE_READY_FOR_REVIEW_NOT_RUNTIME_TESTED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
