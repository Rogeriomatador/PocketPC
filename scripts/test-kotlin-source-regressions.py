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
if 'if (extension == "zip")' not in associations:
    errors.append("ZIP must be the only archive promoted to implemented-internal readiness")
if "PocketFileHandler.PDF_VIEWER" not in associations:
    errors.append("PocketFileAssociations lost PDF ownership")
if "IMPLEMENTED_AUDIO_EXTENSIONS" not in associations:
    errors.append("PocketFileAssociations lost implemented audio readiness")

zip_engine = read("app/src/main/java/dev/pocketpc/core/storage/PocketZipArchive.kt")
for required in (
    "validatePocketZipEntryPath(entry.name)",
    "MAX_ZIP_ENTRY_COUNT",
    "MAX_ZIP_PATH_DEPTH",
    "MAX_ZIP_FILE_BYTES",
    "MAX_ZIP_TOTAL_EXTRACTED_BYTES",
    'require(!normalized.startsWith(\'/\'))',
    'Regex("^[A-Za-z]:")',
    'segment != "." && segment != ".."',
    "WINDOWS_RESERVED_ZIP_NAMES",
    "consumeZipEntryForInspection(",
    "runCatching { extractionRoot?.delete() }",
):
    if required not in zip_engine:
        errors.append(f"PocketZipArchive lost security invariant: {required}")
if "Intent.ACTION_VIEW" in zip_engine:
    errors.append("PocketZipArchive must never delegate extraction to Android ACTION_VIEW")

zip_pane = read("app/src/main/java/dev/pocketpc/core/ui/PocketZipArchivePane.kt")
for required in (
    "inspectPocketZip(context, uri)",
    "extractPocketZipToDownloads(",
    '"Extrair em P:\\\\Downloads"',
):
    if required not in zip_pane:
        errors.append(f"PocketZipArchivePane missing internal ZIP flow: {required}")

zip_creator = read("app/src/main/java/dev/pocketpc/core/storage/PocketZipCreator.kt")
for required in (
    "MAX_CREATE_ZIP_ENTRY_COUNT",
    "MAX_CREATE_ZIP_DEPTH",
    "MAX_CREATE_ZIP_FILE_BYTES",
    "MAX_CREATE_ZIP_TOTAL_BYTES",
    '".pocketpc-part-${System.nanoTime()}-$outputName"',
    "runCatching { target?.delete() }",
    "ZipOutputStream(raw)",
):
    if required not in zip_creator:
        errors.append(f"PocketZipCreator lost transactional/safety invariant: {required}")
if "Intent.ACTION_VIEW" in zip_creator:
    errors.append("PocketZipCreator must never delegate compression to Android ACTION_VIEW")

text_store = read("app/src/main/java/dev/pocketpc/core/storage/PocketTextFileStore.kt")
for required in (
    "MAX_EDITABLE_TEXT_BYTES",
    "readOriginalEditableBytes(",
    'openOutputStream(uri, "rwt")',
    "writeTextBytes(",
):
    if required not in text_store:
        errors.append(f"PocketTextFileStore lost safe-save invariant: {required}")

text_editor = read("app/src/main/java/dev/pocketpc/core/ui/PocketTextEditorPane.kt")
for required in (
    "readOnly = !document.editable",
    "savePocketTextDocument(",
    'Text("Salvar")',
):
    if required not in text_editor:
        errors.append(f"PocketTextEditorPane missing editable-text behavior: {required}")

pdf_viewer = read("app/src/main/java/dev/pocketpc/core/ui/PocketPdfViewerPane.kt")
for required in (
    "PdfRenderer(descriptor)",
    "PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY",
    "openFileDescriptor(Uri.parse(uriString), \"r\")",
    "Página ${state.pageIndex + 1} de ${state.pageCount}",
):
    if required not in pdf_viewer:
        errors.append(f"PocketPdfViewerPane missing internal PDF flow: {required}")
if "Intent.ACTION_VIEW" in pdf_viewer:
    errors.append("PocketPdfViewerPane must never delegate PDF opening to Android ACTION_VIEW")

audio_player = read("app/src/main/java/dev/pocketpc/core/ui/PocketAudioPlayerPane.kt")
for required in (
    "MediaPlayer()",
    "setDataSource(",
    "prepareAsync()",
    'Text(if (playing) "Pausar" else "Reproduzir")',
):
    if required not in audio_player:
        errors.append(f"PocketAudioPlayerPane missing internal playback behavior: {required}")
if "Intent.ACTION_VIEW" in audio_player:
    errors.append("PocketAudioPlayerPane must never delegate audio to Android ACTION_VIEW")

open_overlay = read("app/src/main/java/dev/pocketpc/core/ui/PocketFileOpenOverlay.kt")
for required in (
    "PocketTextEditorPane(request = currentRequest)",
    "PocketZipArchivePane(request = currentRequest)",
    "PocketPdfViewerPane(request = currentRequest)",
    "PocketAudioPlayerPane(request = currentRequest)",
):
    if open_overlay.count(required) != 1:
        errors.append(f"Global file-open overlay must mount exactly one: {required}")

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
print("pocket_zip_security=guarded")
print("pocket_zip_creation=guarded")
print("pocket_text_editor=guarded")
print("pocket_pdf_viewer=guarded")
print("pocket_audio_player=guarded")
