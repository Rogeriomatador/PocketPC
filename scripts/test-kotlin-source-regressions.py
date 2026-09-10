#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]

def read(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        raise SystemExit(f"KOTLIN_REGRESSION_GUARD_MISSING={rel}")
    return path.read_text(encoding="utf-8")

errors: list[str] = []

guest = read("app/src/main/java/dev/pocketpc/core/runtime/GuestRuntimeProbe.kt")
guest_lines = guest.count("\n") + 1
if guest_lines > 1000:
    errors.append(f"GuestRuntimeProbe.kt unexpectedly large: {guest_lines} lines")
if re.search(r"(?m)^\s*}POCKETPC_DISPLAY_", guest):
    errors.append("GuestRuntimeProbe.kt contains interpolation-corruption marker")
if re.search(r'"\$POCKETPC_DISPLAY_', guest):
    errors.append("GuestRuntimeProbe.kt contains unescaped shell display variable")
for name in (
    "POCKETPC_DISPLAY_SOCKET",
    "POCKETPC_DISPLAY_TOKEN",
    "POCKETPC_DISPLAY_RUNTIME_SHA256",
):
    token = "${'$'}" + name
    count = guest.count(token)
    if count != 2:
        errors.append(f"{name} escaped occurrence count={count}, expected=2")

probe = read("app/src/main/java/dev/pocketpc/core/runtime/RuntimeDisplayBridgeProbeController.kt")
if re.search(r"return@coroutineScope\s*\n\s*RuntimeDisplayBridgeProbeResult\(", probe):
    errors.append("RuntimeDisplayBridgeProbeController has split labeled result return")

execution = read("app/src/main/java/dev/pocketpc/core/runtime/RuntimeDisplayExecutionController.kt")
if re.search(r"return@coroutineScope\s*\n\s*RuntimeDisplayExecutionResult\(", execution):
    errors.append("RuntimeDisplayExecutionController has split labeled result return")

supervisor = read("app/src/main/java/dev/pocketpc/core/runtime/RuntimeProcessSupervisor.kt")
if re.search(r"return@withContext\s*\n\s*ProcessRunResult\(", supervisor):
    errors.append("RuntimeProcessSupervisor has split labeled ProcessRunResult return")

desktop = read("app/src/main/java/dev/pocketpc/core/ui/RuntimeDesktopWindowLayer.kt")
if "native.metaState" in desktop:
    errors.append("RuntimeDesktopWindowLayer contains removed native.metaState reference")

task_manager = read("app/src/main/java/dev/pocketpc/core/ui/TaskManagerApp.kt")
if "private fun formatBytes(" in task_manager:
    errors.append("TaskManagerApp reintroduced ambiguous formatBytes helper")
if task_manager.count("private fun formatTaskManagerBytes(") != 1:
    errors.append("TaskManagerApp formatTaskManagerBytes helper count is not exactly one")

# Opening files from PocketPC-owned surfaces must fail closed inside PocketPC.
# BrowserApp has one explicit opt-in Android escape hatch for web pages; it is
# deliberately excluded from the generic-file prohibition below.
for rel in (
    "app/src/main/java/dev/pocketpc/core/storage/StorageRepository.kt",
    "app/src/main/java/dev/pocketpc/core/ui/DownloadsApp.kt",
    "app/src/main/java/dev/pocketpc/core/ui/FilesApp.kt",
):
    source = read(rel)
    if "Intent.ACTION_VIEW" in source:
        errors.append(f"{Path(rel).name} reintroduced generic Android ACTION_VIEW")

storage = read("app/src/main/java/dev/pocketpc/core/storage/StorageRepository.kt")
for required in (
    "PocketFileOpenCoordinator.present(",
    "uri = entry.uri",
    "mimeType = entry.mimeType",
    "sizeBytes = entry.size",
):
    if required not in storage:
        errors.append(f"StorageRepository missing PocketPC file-open request field: {required}")

main_activity = read("app/src/main/java/dev/pocketpc/core/MainActivity.kt")
if main_activity.count("PocketFileOpenOverlay()") != 1:
    errors.append("MainActivity must mount exactly one global PocketFileOpenOverlay")

browser = read("app/src/main/java/dev/pocketpc/core/ui/BrowserApp.kt")
if "Abrir fora do PocketPC (Android)" not in browser:
    errors.append("Browser explicit Android escape-hatch label is missing")
if "O PocketPC bloqueou a saída automática para o Android." not in browser:
    errors.append("Browser no longer fail-closes non-web URL schemes")

associations = read("app/src/main/java/dev/pocketpc/core/storage/PocketFileAssociations.kt")
if "PocketFileHandlerReadiness.IMPLEMENTED_INTERNAL" not in associations:
    errors.append("PocketFileAssociations lost implemented-internal readiness tracking")

if errors:
    for error in errors:
        print(f"KOTLIN_SOURCE_REGRESSION_GUARD_FAIL: {error}", file=sys.stderr)
    raise SystemExit(1)

print("KOTLIN_SOURCE_REGRESSION_GUARDS_OK")
print(f"guest_runtime_probe_lines={guest_lines}")
print("display_shell_variables=escaped")
print("labeled_return_regressions=absent")
print("task_manager_formatter=isolated")
print("pocket_file_open_boundary=guarded")
print("pocket_file_overlay=global")
print("browser_external_escape=explicit_only")
