#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]

ROUTER = ROOT / "app/src/main/java/dev/pocketpc/core/storage/PocketFileRouter.kt"
STORAGE = ROOT / "app/src/main/java/dev/pocketpc/core/storage/StorageRepository.kt"
DOWNLOADS = ROOT / "app/src/main/java/dev/pocketpc/core/ui/DownloadsApp.kt"
BROWSER = ROOT / "app/src/main/java/dev/pocketpc/core/ui/BrowserApp.kt"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
ROUTER_TEST = ROOT / "app/src/test/java/dev/pocketpc/core/storage/PocketFileRouterTest.kt"


def read(path: pathlib.Path) -> str:
    if not path.is_file():
        raise AssertionError(f"missing required file: {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise AssertionError(f"{label} missing sentinel: {needle}")


def forbid(text: str, needle: str, label: str) -> None:
    if needle in text:
        raise AssertionError(f"{label} forbidden escape detected: {needle}")


def main() -> int:
    router = read(ROUTER)
    storage = read(STORAGE)
    downloads = read(DOWNLOADS)
    browser = read(BROWSER)
    manifest = read(MANIFEST)
    tests = read(ROUTER_TEST)

    for sentinel in (
        "enum class PocketFileRoute",
        "PC_RUNTIME",
        "ANDROID_PACKAGE_INSTALLER",
        "POCKET_ARCHIVE",
        "POCKET_DISK_IMAGE",
        "POCKET_INTERNAL_APP",
        "POCKET_UNSUPPORTED",
        "fun routePocketFile(",
        'setOf("exe", "msi", "bat", "cmd", "com", "scr")',
        'setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "cab")',
    ):
        require(router, sentinel, "PocketFileRouter.kt")

    require(storage, "routePocketFile(", "StorageRepository.kt")
    require(storage, "PocketFileRoute.ANDROID_PACKAGE_INSTALLER", "StorageRepository.kt")
    require(storage, "Intent.ACTION_INSTALL_PACKAGE", "StorageRepository.kt")
    forbid(storage, "Intent(Intent.ACTION_VIEW)", "StorageRepository.kt")
    forbid(storage, "Intent.ACTION_VIEW).apply", "StorageRepository.kt")

    require(downloads, "routePocketFile(", "DownloadsApp.kt")
    require(downloads, "PocketFileRoute.PC_RUNTIME", "DownloadsApp.kt")
    require(downloads, 'Text("Fila PocketPC")', "DownloadsApp.kt")
    forbid(downloads, "Intent.ACTION_VIEW", "DownloadsApp.kt")
    forbid(downloads, "DownloadManager.ACTION_VIEW_DOWNLOADS", "DownloadsApp.kt")
    forbid(downloads, "PocketOpenRoute", "DownloadsApp.kt")

    require(browser, "DownloadManager.Request.VISIBILITY_HIDDEN", "BrowserApp.kt")
    require(browser, '"Abrir fora do PocketPC (Android)"', "BrowserApp.kt")
    require(browser, 'scheme in setOf("http", "https", "about", "data", "blob")', "BrowserApp.kt")
    require(browser, "O PocketPC bloqueou a saída automática para o Android", "BrowserApp.kt")
    require(browser, "downloadStatus", "BrowserApp.kt")
    forbid(browser, "VISIBILITY_VISIBLE_NOTIFY_COMPLETED", "BrowserApp.kt")
    forbid(browser, "setDestinationInExternalPublicDir", "BrowserApp.kt")
    forbid(browser, "Toast.makeText", "BrowserApp.kt")

    require(
        manifest,
        'android.permission.DOWNLOAD_WITHOUT_NOTIFICATION',
        "AndroidManifest.xml",
    )

    require(tests, "winrar", "PocketFileRouterTest.kt")
    require(tests, "PocketFileRoute.PC_RUNTIME", "PocketFileRouterTest.kt")

    print("POCKET_FILE_ROUTING_POLICY_OK")
    print("generic_android_action_view=false")
    print("pc_runtime_route=true")
    print("android_package_escape=explicit_only")
    print("browser_external_schemes=blocked_by_default")
    print("browser_download_notification=hidden")
    print("browser_download_staging=app_private")
    print("router_unit_test_present=true")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as error:
        print(f"POCKET_FILE_ROUTING_POLICY_FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
