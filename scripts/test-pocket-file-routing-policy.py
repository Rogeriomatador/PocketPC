#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]

ROUTER = ROOT / "app/src/main/java/dev/pocketpc/core/storage/PocketFileRouter.kt"
ASSOCIATIONS = ROOT / "app/src/main/java/dev/pocketpc/core/storage/PocketFileAssociations.kt"
OPEN_PLAN = ROOT / "app/src/main/java/dev/pocketpc/core/storage/PocketFileOpenPlan.kt"
COORDINATOR = ROOT / "app/src/main/java/dev/pocketpc/core/storage/PocketFileOpenCoordinator.kt"
OVERLAY = ROOT / "app/src/main/java/dev/pocketpc/core/ui/PocketFileOpenOverlay.kt"
MAIN = ROOT / "app/src/main/java/dev/pocketpc/core/MainActivity.kt"
POCKET_DRIVE = ROOT / "app/src/main/java/dev/pocketpc/core/storage/PocketDrive.kt"
STORAGE = ROOT / "app/src/main/java/dev/pocketpc/core/storage/StorageRepository.kt"
DOWNLOADS = ROOT / "app/src/main/java/dev/pocketpc/core/ui/DownloadsApp.kt"
BROWSER = ROOT / "app/src/main/java/dev/pocketpc/core/ui/BrowserApp.kt"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
ROUTER_TEST = ROOT / "app/src/test/java/dev/pocketpc/core/storage/PocketFileRouterTest.kt"
ASSOCIATIONS_TEST = ROOT / "app/src/test/java/dev/pocketpc/core/storage/PocketFileAssociationsTest.kt"
OPEN_PLAN_TEST = ROOT / "app/src/test/java/dev/pocketpc/core/storage/PocketFileOpenPlanTest.kt"
DRIVE_TEST = ROOT / "app/src/test/java/dev/pocketpc/core/storage/PocketDriveTest.kt"


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
    associations = read(ASSOCIATIONS)
    open_plan = read(OPEN_PLAN)
    coordinator = read(COORDINATOR)
    overlay = read(OVERLAY)
    main = read(MAIN)
    pocket_drive = read(POCKET_DRIVE)
    storage = read(STORAGE)
    downloads = read(DOWNLOADS)
    browser = read(BROWSER)
    manifest = read(MANIFEST)
    router_tests = read(ROUTER_TEST)
    association_tests = read(ASSOCIATIONS_TEST)
    open_plan_tests = read(OPEN_PLAN_TEST)
    drive_tests = read(DRIVE_TEST)

    for sentinel in (
        "enum class PocketFileRoute",
        "PC_RUNTIME",
        "ANDROID_PACKAGE_INSTALLER",
        "POCKET_ARCHIVE",
        "POCKET_DISK_IMAGE",
        "POCKET_INTERNAL_APP",
        "POCKET_UNSUPPORTED",
        "fun routePocketFile(",
        "PC_EXECUTABLE_EXTENSIONS",
        "ARCHIVE_EXTENSIONS",
        "DISK_IMAGE_EXTENSIONS",
        '"exe"', '"msi"', '"msix"', '"bat"', '"cmd"',
        '"rar"', '"cab"', '"docx"', '"xlsx"', '"pptx"', '"html"',
    ):
        require(router, sentinel, "PocketFileRouter.kt")

    for sentinel in (
        "enum class PocketFileHandler",
        "WINDOWS_RUNTIME",
        "ANDROID_PACKAGE_INSTALLER",
        "ARCHIVE_MANAGER",
        "DISK_IMAGE_MANAGER",
        "TEXT_EDITOR",
        "IMAGE_VIEWER",
        "PDF_VIEWER",
        "MEDIA_PLAYER",
        "OFFICE_VIEWER",
        "WEB_DOCUMENT",
        "fun resolvePocketFileAssociation(",
        "ANDROID_SYSTEM_REQUIRED",
        "ROUTE_ONLY",
    ):
        require(associations, sentinel, "PocketFileAssociations.kt")

    for sentinel in (
        "enum class PocketFileOpenCapability",
        "WINDOWS_RUNTIME_REQUIRED",
        "ANDROID_SYSTEM_ACTION_REQUIRED",
        "INTERNAL_HANDLER_PENDING",
        "UNSUPPORTED",
        "fun planPocketFileOpen(",
        "canAttemptNow",
        "leavesPocketPc",
    ):
        require(open_plan, sentinel, "PocketFileOpenPlan.kt")

    for sentinel in (
        "object PocketFileOpenCoordinator",
        "MutableStateFlow<PocketFileOpenPlan?>",
        "fun present(",
        "fun dismiss()",
    ):
        require(coordinator, sentinel, "PocketFileOpenCoordinator.kt")

    for sentinel in (
        "fun PocketFileOpenOverlay()",
        "PocketFileOpenCoordinator",
        'Text("Entendi")',
        '"O arquivo continua dentro do PocketPC.',
    ):
        require(overlay, sentinel, "PocketFileOpenOverlay.kt")

    require(main, "PocketFileOpenOverlay()", "MainActivity.kt")

    for sentinel in ('"bat"', '"cmd"', '"com"', '"scr"', '"cab"', '"bz2"'):
        require(pocket_drive, sentinel, "PocketDrive.kt")

    require(storage, "planPocketFileOpen(", "StorageRepository.kt")
    require(storage, "PocketFileOpenCapability.ANDROID_SYSTEM_ACTION_REQUIRED", "StorageRepository.kt")
    require(storage, "PocketFileOpenCoordinator.present(plan)", "StorageRepository.kt")
    require(storage, "Intent.ACTION_INSTALL_PACKAGE", "StorageRepository.kt")
    forbid(storage, "Intent(Intent.ACTION_VIEW)", "StorageRepository.kt")
    forbid(storage, "Intent.ACTION_VIEW).apply", "StorageRepository.kt")

    for sentinel in (
        "planPocketFileOpen(",
        "PocketFileOpenCapability.WINDOWS_RUNTIME_REQUIRED",
        "PocketFileOpenCapability.ANDROID_SYSTEM_ACTION_REQUIRED",
        "PocketFileOpenCoordinator.present(plan)",
        'Text("Fila PocketPC")',
        "PcApplicationTarget",
    ):
        require(downloads, sentinel, "DownloadsApp.kt")
    forbid(downloads, "Intent.ACTION_VIEW", "DownloadsApp.kt")
    forbid(downloads, "DownloadManager.ACTION_VIEW_DOWNLOADS", "DownloadsApp.kt")
    forbid(downloads, "PocketOpenRoute", "DownloadsApp.kt")
    forbid(downloads, "PocketFileRoutingDecision", "DownloadsApp.kt")

    require(browser, "DownloadManager.Request.VISIBILITY_HIDDEN", "BrowserApp.kt")
    require(browser, '"Abrir fora do PocketPC (Android)"', "BrowserApp.kt")
    require(browser, 'scheme in setOf("http", "https", "about", "data", "blob")', "BrowserApp.kt")
    require(browser, "O PocketPC bloqueou a saída automática para o Android", "BrowserApp.kt")
    require(browser, "downloadStatus", "BrowserApp.kt")
    require(browser, "openOutsidePocketPc", "BrowserApp.kt")
    forbid(browser, "VISIBILITY_VISIBLE_NOTIFY_COMPLETED", "BrowserApp.kt")
    forbid(browser, "setDestinationInExternalPublicDir", "BrowserApp.kt")
    forbid(browser, "Toast.makeText", "BrowserApp.kt")
    forbid(browser, "activityContext.startActivity(Intent(Intent.ACTION_VIEW", "BrowserApp.kt")
    if browser.count("Intent.ACTION_VIEW") != 1:
        raise AssertionError(
            "BrowserApp.kt must have exactly one ACTION_VIEW, reserved for explicit 'Abrir fora do PocketPC'"
        )

    require(manifest, 'android.permission.DOWNLOAD_WITHOUT_NOTIFICATION', "AndroidManifest.xml")

    require(router_tests, "winrar", "PocketFileRouterTest.kt")
    require(router_tests, "PocketFileRoute.PC_RUNTIME", "PocketFileRouterTest.kt")
    require(association_tests, "PocketFileHandler.OFFICE_VIEWER", "PocketFileAssociationsTest.kt")
    require(association_tests, "PocketFileHandler.WEB_DOCUMENT", "PocketFileAssociationsTest.kt")
    require(association_tests, "PocketFileHandler.NONE", "PocketFileAssociationsTest.kt")
    require(open_plan_tests, "winrar-x64.exe", "PocketFileOpenPlanTest.kt")
    require(open_plan_tests, "INTERNAL_HANDLER_PENDING", "PocketFileOpenPlanTest.kt")
    require(open_plan_tests, "assertFalse(plan.leavesPocketPc)", "PocketFileOpenPlanTest.kt")
    require(drive_tests, 'classifyPocketFile("bootstrap.bat")', "PocketDriveTest.kt")
    require(drive_tests, 'classifyPocketFile("package.cab")', "PocketDriveTest.kt")

    print("POCKET_FILE_ROUTING_POLICY_OK")
    print("generic_android_action_view=false")
    print("pc_runtime_route=true")
    print("android_package_escape=explicit_only")
    print("browser_external_schemes=blocked_by_default")
    print("browser_explicit_android_exit_count=1")
    print("browser_download_notification=hidden")
    print("browser_download_staging=app_private")
    print("file_associations=centralized")
    print("file_open_plan=centralized")
    print("file_open_overlay=mounted")
    print("downloads_use_open_overlay=true")
    print("desktop_document_ownership=PocketPC")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as error:
        print(f"POCKET_FILE_ROUTING_POLICY_FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
