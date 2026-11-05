#!/usr/bin/env python3
"""Execute PocketPC Wine Vulkan headless-surface diagnostics on host x86_64.

PASS proves the pinned Wine build loaded winepocketpc.drv, initialized the
private Vulkan driver ABI, mapped VK_KHR_win32_surface to the host headless
surface path, and created/destroyed a VkSurfaceKHR. It deliberately does not
claim Android visibility, swapchain presentation, DXVK, Box64, PRoot, or Roblox.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time

from pocketpc_wine_surface_broker import SurfaceSmokeBroker

ROOT = Path(__file__).resolve().parents[1]
LOAD_SCRIPT = ROOT / "scripts/run-wine-pocketpc-driver-load-smoke.py"
spec = importlib.util.spec_from_file_location("pocketpc_driver_load_helpers", LOAD_SCRIPT)
load_helpers = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(load_helpers)

C_SOURCE = r"""
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

typedef uint32_t VkFlags;
typedef uint32_t VkBool32;
typedef int32_t VkResult;
typedef uint64_t VkSurfaceKHR;
typedef struct VkInstance_T *VkInstance;

#define VK_SUCCESS 0
#define VK_STRUCTURE_TYPE_APPLICATION_INFO 0
#define VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO 1
#define VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR 1000009000

typedef struct VkApplicationInfo {
    uint32_t sType;
    const void *pNext;
    const char *pApplicationName;
    uint32_t applicationVersion;
    const char *pEngineName;
    uint32_t engineVersion;
    uint32_t apiVersion;
} VkApplicationInfo;

typedef struct VkInstanceCreateInfo {
    uint32_t sType;
    const void *pNext;
    VkFlags flags;
    const VkApplicationInfo *pApplicationInfo;
    uint32_t enabledLayerCount;
    const char *const *ppEnabledLayerNames;
    uint32_t enabledExtensionCount;
    const char *const *ppEnabledExtensionNames;
} VkInstanceCreateInfo;

typedef struct VkWin32SurfaceCreateInfoKHR {
    uint32_t sType;
    const void *pNext;
    VkFlags flags;
    HINSTANCE hinstance;
    HWND hwnd;
} VkWin32SurfaceCreateInfoKHR;

typedef VkResult (WINAPI *PFN_vkCreateInstance)(
    const VkInstanceCreateInfo *, const void *, VkInstance *);
typedef void * (WINAPI *PFN_vkGetInstanceProcAddr)(VkInstance, const char *);
typedef void (WINAPI *PFN_vkDestroyInstance)(VkInstance, const void *);
typedef VkResult (WINAPI *PFN_vkCreateWin32SurfaceKHR)(
    VkInstance, const VkWin32SurfaceCreateInfoKHR *, const void *, VkSurfaceKHR *);
typedef void (WINAPI *PFN_vkDestroySurfaceKHR)(VkInstance, VkSurfaceKHR, const void *);

static LRESULT CALLBACK wndproc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp)
{
    if (msg == WM_CLOSE) {
        DestroyWindow(hwnd);
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

int main(void)
{
    HMODULE vulkan = NULL;
    FARPROC create_instance_raw = NULL;
    FARPROC get_proc_raw = NULL;
    PFN_vkCreateInstance create_instance = NULL;
    PFN_vkGetInstanceProcAddr get_proc = NULL;
    PFN_vkDestroyInstance destroy_instance;
    PFN_vkCreateWin32SurfaceKHR create_surface;
    PFN_vkDestroySurfaceKHR destroy_surface;
    VkApplicationInfo app = {0};
    VkInstanceCreateInfo instance_info = {0};
    VkWin32SurfaceCreateInfoKHR surface_info = {0};
    const char *extensions[] = {"VK_KHR_surface", "VK_KHR_win32_surface"};
    VkInstance instance = NULL;
    VkSurfaceKHR surface = 0;
    WNDCLASSW wc = {0};
    HWND hwnd = NULL;
    VkResult vr;

    vulkan = LoadLibraryW(L"vulkan-1.dll");
    if (!vulkan) {
        printf("POCKETPC_VULKAN_HEADLESS_SMOKE_LOAD_FAILED error=%lu\n", (unsigned long)GetLastError());
        return 10;
    }

    create_instance_raw = GetProcAddress(vulkan, "vkCreateInstance");
    get_proc_raw = GetProcAddress(vulkan, "vkGetInstanceProcAddr");
    if (!create_instance_raw || !get_proc_raw) {
        printf("POCKETPC_VULKAN_HEADLESS_SMOKE_EXPORTS_MISSING\n");
        return 11;
    }

    _Static_assert(sizeof(create_instance) == sizeof(create_instance_raw),
        "Win32/Vulkan function pointer size mismatch");
    _Static_assert(sizeof(get_proc) == sizeof(get_proc_raw),
        "Win32/Vulkan function pointer size mismatch");
    memcpy(&create_instance, &create_instance_raw, sizeof(create_instance));
    memcpy(&get_proc, &get_proc_raw, sizeof(get_proc));

    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "PocketPC Vulkan Headless Smoke";
    app.pEngineName = "PocketPC";
    app.apiVersion = (1u << 22);

    instance_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instance_info.pApplicationInfo = &app;
    instance_info.enabledExtensionCount = 2;
    instance_info.ppEnabledExtensionNames = extensions;

    vr = create_instance(&instance_info, NULL, &instance);
    if (vr != VK_SUCCESS || !instance) {
        printf("POCKETPC_VULKAN_HEADLESS_SMOKE_INSTANCE_FAILED result=%ld\n", (long)vr);
        return 12;
    }

    destroy_instance = (PFN_vkDestroyInstance)get_proc(instance, "vkDestroyInstance");
    create_surface = (PFN_vkCreateWin32SurfaceKHR)get_proc(instance, "vkCreateWin32SurfaceKHR");
    destroy_surface = (PFN_vkDestroySurfaceKHR)get_proc(instance, "vkDestroySurfaceKHR");
    if (!destroy_instance || !create_surface || !destroy_surface) {
        printf("POCKETPC_VULKAN_HEADLESS_SMOKE_INSTANCE_EXPORTS_MISSING\n");
        return 13;
    }

    wc.lpfnWndProc = wndproc;
    wc.hInstance = GetModuleHandleW(NULL);
    wc.lpszClassName = L"PocketPcVulkanHeadlessSmokeWindow";
    if (!RegisterClassW(&wc) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
        printf("POCKETPC_VULKAN_HEADLESS_SMOKE_REGISTER_FAILED error=%lu\n", (unsigned long)GetLastError());
        return 14;
    }

    hwnd = CreateWindowExW(
        0, wc.lpszClassName, L"PocketPC Vulkan Headless Smoke",
        WS_OVERLAPPEDWINDOW, 0, 0, 64, 64,
        NULL, NULL, wc.hInstance, NULL);
    if (!hwnd) {
        printf("POCKETPC_VULKAN_HEADLESS_SMOKE_WINDOW_FAILED error=%lu\n", (unsigned long)GetLastError());
        return 15;
    }

    surface_info.sType = VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR;
    surface_info.hinstance = wc.hInstance;
    surface_info.hwnd = hwnd;

    vr = create_surface(instance, &surface_info, NULL, &surface);
    if (vr != VK_SUCCESS || !surface) {
        printf("POCKETPC_VULKAN_HEADLESS_SMOKE_SURFACE_FAILED result=%ld\n", (long)vr);
        return 16;
    }

    printf("POCKETPC_VULKAN_HEADLESS_SURFACE_CREATED_OK\n");
    destroy_surface(instance, surface, NULL);
    DestroyWindow(hwnd);
    destroy_instance(instance, NULL);
    FreeLibrary(vulkan);
    printf("POCKETPC_VULKAN_HEADLESS_SMOKE_OK\n");
    return 0;
}
"""

REQUIRED_MARKERS = (
    "POCKETPC_DRIVER_LOAD stage=user_driver_registered protocol=4",
    "POCKETPC_VULKAN_WSI stage=abi_initialized",
    "POCKETPC_VULKAN_WSI stage=headless_surface_created",
    "POCKETPC_VULKAN_HEADLESS_SURFACE_CREATED_OK",
    "POCKETPC_VULKAN_HEADLESS_SMOKE_OK",
)


def load_json(path: Path) -> dict[str, object]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise RuntimeError(f"JSON is not an object: {path}")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wine-root", type=Path, required=True)
    parser.add_argument("--package-evidence", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--timeout-seconds", type=float, default=35.0)
    args = parser.parse_args()

    work = args.work.resolve()
    if work.exists():
        raise SystemExit("WINE_VULKAN_HEADLESS_SMOKE_WORK_ALREADY_EXISTS")
    work.mkdir(parents=True)
    source = work / "pocketpc-vulkan-headless-smoke.c"
    smoke = work / "pocketpc-vulkan-headless-smoke.exe"
    log_path = work / "wine-vulkan-headless-smoke.log"
    evidence_path = work / "wine-vulkan-headless-smoke-evidence.json"

    evidence: dict[str, object] = {
        "schema": 1,
        "status": "NOT_EXECUTED",
        "scope": "host_x86_64_wine_vulkan_headless_surface",
        "claims": {
            "wine_user_driver_registered": False,
            "vulkan_driver_abi_initialized": False,
            "win32_surface_extension_mapped": False,
            "headless_vk_surface_created": False,
            "visible_surface_presented": False,
            "swapchain_presented": False,
            "dxvk_executed": False,
            "android_executed": False,
            "proot_executed": False,
            "box64_executed": False,
            "roblox_executed": False,
            "physical_validation": False,
        },
    }

    runtime_env = None
    wine_root = None
    wine = None
    broker = None

    try:
        package = load_json(args.package_evidence)
        if package.get("status") != "pass" or package.get("scope") != "full_wine_package_driver_static_identity":
            raise RuntimeError("full Wine package static evidence is invalid")

        wine_root = args.wine_root.resolve()
        wine = wine_root / "bin/wine"
        if not wine.is_file():
            raise RuntimeError(f"Wine entrypoint missing: {wine}")

        source.write_text(C_SOURCE, encoding="utf-8")
        compile_result = subprocess.run(
            [
                "x86_64-w64-mingw32-gcc",
                "-O2",
                "-Wall",
                "-Wextra",
                "-Werror",
                str(source),
                "-o",
                str(smoke),
                "-lgdi32",
                "-luser32",
                "-lkernel32",
            ],
            cwd=work,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            check=False,
        )
        (work / "compile.log").write_text(compile_result.stdout, encoding="utf-8")
        if compile_result.returncode != 0 or not smoke.is_file():
            evidence["compile"] = {
                "returnCode": compile_result.returncode,
                "logTail": compile_result.stdout[-8000:],
            }
            raise RuntimeError(f"Win64 Vulkan fixture compile failed rc={compile_result.returncode}")

        runtime_env, relocation = load_helpers.relocated_wine_env(wine_root, wine, package)
        evidence["relocation"] = relocation
        prefix = work / "prefix"
        runtime_env.update({"WINEPREFIX": str(prefix), "WINEARCH": "win64", "WINEDEBUG": "-all"})
        runtime_env.pop("DISPLAY", None)
        runtime_env.pop("WAYLAND_DISPLAY", None)

        wineboot = load_helpers.helper(wine_root, "wineboot", wine)
        boot_rc = load_helpers.run_logged(
            wineboot + ["-u"], work, runtime_env, work / "wineboot.log", timeout=90
        )
        if boot_rc != 0:
            raise RuntimeError(f"wineboot failed rc={boot_rc}")

        reg = load_helpers.helper(wine_root, "reg", wine)
        add_rc = load_helpers.run_logged(
            reg + [
                "add",
                r"HKCU\Software\Wine\Drivers",
                "/v",
                "Graphics",
                "/t",
                "REG_SZ",
                "/d",
                "pocketpc",
                "/f",
            ],
            work,
            runtime_env,
            work / "registry-add.log",
            timeout=30,
        )
        if add_rc != 0:
            raise RuntimeError(f"Wine registry add failed rc={add_rc}")

        load_helpers.stop_wineserver(wine_root, wine, runtime_env, work, "before-vulkan-smoke")

        token = os.urandom(32)
        identity = load_helpers.sha256(wine).encode("ascii")
        broker = SurfaceSmokeBroker(token, identity)
        broker.start()

        run_env = runtime_env.copy()
        run_env.update(
            {
                "WINEDEBUG": "+pocketpcdrv",
                "POCKETPC_VULKAN_HEADLESS_DIAGNOSTIC": "1",
                "POCKETPC_DISPLAY_PROTOCOL": "4",
                "POCKETPC_DISPLAY_SOCKET": broker.socket_name,
                "POCKETPC_DISPLAY_TOKEN": token.hex(),
                "POCKETPC_DISPLAY_RUNTIME_SHA256": identity.decode("ascii"),
                "POCKETPC_DISPLAY_HOST_CAPS": "23",
            }
        )

        with log_path.open("w", encoding="utf-8") as out:
            process = subprocess.Popen(
                [str(wine), str(smoke)],
                cwd=work,
                env=run_env,
                stdout=out,
                stderr=subprocess.STDOUT,
                text=True,
            )

        deadline = time.monotonic() + max(5.0, args.timeout_seconds)
        while process.poll() is None and time.monotonic() < deadline:
            snapshot = broker.snapshot()
            if snapshot["errors"]:
                break
            time.sleep(0.1)

        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            raise RuntimeError("Win64 Vulkan headless smoke timed out")

        return_code = process.returncode
        broker.close()
        snapshot = broker.snapshot()
        evidence["broker"] = snapshot
        if snapshot["errors"]:
            raise RuntimeError("display broker failed: " + " | ".join(str(x) for x in snapshot["errors"]))
        if return_code != 0:
            raise RuntimeError(f"Win64 Vulkan headless smoke exited rc={return_code}")

        log = log_path.read_text(encoding="utf-8", errors="replace")
        missing = [marker for marker in REQUIRED_MARKERS if marker not in log]
        if missing:
            raise RuntimeError("required Vulkan diagnostic markers missing: " + ", ".join(missing))

        claims = evidence["claims"]
        assert isinstance(claims, dict)
        claims["wine_user_driver_registered"] = True
        claims["vulkan_driver_abi_initialized"] = True
        claims["win32_surface_extension_mapped"] = True
        claims["headless_vk_surface_created"] = True
        evidence["status"] = "PASS_HOST_WINE_VULKAN_HEADLESS_SURFACE_NOT_VISIBLE"
        evidence["wine"] = {"path": str(wine), "sha256": load_helpers.sha256(wine)}
        evidence["smokeExe"] = {"path": str(smoke), "sha256": load_helpers.sha256(smoke)}
        evidence_path.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    except Exception as exc:
        if broker is not None:
            try:
                broker.close()
            except Exception:
                pass
        evidence["status"] = "FAILED"
        evidence["failure"] = f"{exc.__class__.__name__}:{exc}"
        evidence_path.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print("WINE_POCKETPC_VULKAN_HEADLESS_SMOKE_FAILED=" + str(evidence["failure"]))
        print(f"evidence={evidence_path}")
        return 1
    finally:
        if runtime_env is not None and wine_root is not None and wine is not None:
            load_helpers.stop_wineserver(wine_root, wine, runtime_env, work, "final")

    print("WINE_POCKETPC_VULKAN_HEADLESS_SMOKE_OK")
    print("vulkan_driver_abi_initialized=true")
    print("headless_vk_surface_created=true")
    print("visible_surface_presented=false")
    print("swapchain_presented=false")
    print("dxvk_executed=false")
    print("android_executed=false")
    print("proot_executed=false")
    print("box64_executed=false")
    print("roblox_executed=false")
    print("physical_validation=false")
    print(f"evidence={evidence_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
