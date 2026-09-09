#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
DRIVER = ROOT / "third_party/wine/pocketpc-driver"
LOCK = ROOT / "third_party/wine/LOCK.json"

PINNED_WINE_COMMIT = (
    "db11d0fe6a169c457e23d007e20404643d067aa8"
)

FILES = {
    "makefile": DRIVER / "Makefile.in",
    "dllmain": DRIVER / "dllmain.c",
    "main": DRIVER / "pocketpcdrv_main.c",
    "input": DRIVER / "input.c",
    "surface": DRIVER / "surface.c",
    "window": DRIVER / "window.c",
    "driver_header": DRIVER / "pocketpcdrv.h",
}


def require(
    failures: list[str],
    label: str,
    text: str,
    markers: tuple[str, ...],
) -> None:
    for marker in markers:
        if marker not in text:
            failures.append(
                f"{label} missing: {marker}"
            )


def main() -> int:
    failures: list[str] = []

    import json

    try:
        lock = json.loads(
            LOCK.read_text(encoding="utf-8"),
        )
    except Exception as error:
        print(
            "WINE_POCKETPC_DRIVER_ABI_LOCK_FAILED",
            file=sys.stderr,
        )
        print(f"- lock: {error}", file=sys.stderr)
        return 1

    if lock.get("commit") != PINNED_WINE_COMMIT:
        failures.append(
            "pinned Wine ABI commit changed"
        )
    if lock.get("version") != "11.0":
        failures.append(
            "pinned Wine ABI version changed"
        )

    texts: dict[str, str] = {}
    for label, file in FILES.items():
        if not file.is_file():
            failures.append(
                f"missing ABI file: {file.relative_to(ROOT)}"
            )
            continue
        texts[label] = file.read_text(
            encoding="utf-8",
        )

    require(
        failures,
        "driver Makefile",
        texts.get("makefile", ""),
        (
            "MODULE = winepocketpc.drv",
            "UNIXLIB = winepocketpc.so",
            "UNIX_LIBS = -lwin32u $(PTHREAD_LIBS)",
            "IMPORTS = user32 win32u",
        ),
    )

    # Wine 11.0 pinned user_driver_funcs ABI:
    # BOOL pCreateWindow(HWND)
    # void pDestroyWindow(HWND)
    # BOOL pProcessEvents(DWORD)
    # BOOL pCreateWindowSurface(HWND,BOOL,const RECT*,struct window_surface**)
    # BOOL pWindowPosChanging(HWND,UINT,BOOL,const struct window_rects*)
    # void pWindowPosChanged(HWND,HWND,HWND,UINT,const struct window_rects*,struct window_surface*)
    require(
        failures,
        "user driver registration",
        texts.get("main", ""),
        (
            ".pCreateWindow =",
            "POCKETPC_CreateWindow",
            ".pDestroyWindow =",
            "POCKETPC_DestroyWindow",
            ".pProcessEvents =",
            "POCKETPC_ProcessEvents",
            ".pCreateWindowSurface =",
            "POCKETPC_CreateWindowSurface",
            ".pWindowPosChanging =",
            "POCKETPC_WindowPosChanging",
            ".pWindowPosChanged =",
            "POCKETPC_WindowPosChanged",
            "__wine_set_user_driver(",
            "WINE_GDI_DRIVER_VERSION",
        ),
    )
    require(
        failures,
        "callback declarations",
        texts.get("driver_header", ""),
        (
            "BOOL POCKETPC_CreateWindow(HWND hwnd);",
            "BOOL POCKETPC_ProcessEvents(DWORD mask);",
            "void POCKETPC_DestroyWindow(HWND hwnd);",
            "BOOL POCKETPC_CreateWindowSurface(",
            "BOOL POCKETPC_WindowPosChanging(",
            "void POCKETPC_WindowPosChanged(",
        ),
    )

    # Wine 11.0 pinned window_surface_funcs ABI:
    # set_clip(surface, rects, count)
    # flush(surface, rect, dirty, color_info, color_bits,
    #       shape_changed, shape_info, shape_bits)
    # destroy(surface)
    require(
        failures,
        "window surface ABI",
        texts.get("surface", ""),
        (
            "static void pocketpc_surface_set_clip(",
            "struct window_surface *surface,",
            "const RECT *rects,",
            "UINT count",
            "static BOOL pocketpc_surface_flush(",
            "const RECT *rect,",
            "const RECT *dirty,",
            "const BITMAPINFO *color_info,",
            "const void *color_bits,",
            "BOOL shape_changed,",
            "const BITMAPINFO *shape_info,",
            "const void *shape_bits",
            "static void pocketpc_surface_destroy(",
            "static const struct window_surface_funcs",
            "pocketpc_surface_set_clip,",
            "pocketpc_surface_flush,",
            "pocketpc_surface_destroy",
            "window_surface_create(",
        ),
    )

    # ntuser.h at the pinned Wine commit exposes:
    # NtUserSendHardwareInput(HWND, UINT, const INPUT *, LPARAM)
    require(
        failures,
        "input ABI",
        texts.get("input", ""),
        (
            "NtUserSendHardwareInput(",
            "hwnd,",
            "&input,",
        ),
    )

    # The PE shim must remain PE-only; the implementation files are
    # unixlib sources selected by Wine's '#pragma makedep unix'.
    if "#pragma makedep unix" in texts.get(
        "dllmain",
        "",
    ):
        failures.append(
            "PE dllmain must not be marked Unix-only"
        )

    for label in (
        "main",
        "input",
        "surface",
        "window",
    ):
        require(
            failures,
            f"{label} Unix classification",
            texts.get(label, ""),
            (
                "#if 0",
                "#pragma makedep unix",
                "#endif",
            ),
        )

    if failures:
        print(
            "WINE_POCKETPC_DRIVER_ABI_LOCK_FAILED",
            file=sys.stderr,
        )
        for failure in failures:
            print(
                "- " + failure,
                file=sys.stderr,
            )
        return 1

    print("WINE_POCKETPC_DRIVER_ABI_LOCK_OK")
    print("wine_version=11.0")
    print(
        "wine_commit=" +
        PINNED_WINE_COMMIT
    )
    print("abi_source=pinned-wine11-static-review")
    print("compiler_execution_evidence=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
