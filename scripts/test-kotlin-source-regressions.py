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

# PocketPC-owned file surfaces must never silently escape to Android ACTION_VIEW.
for rel in (
    "app/src/main/java/dev/pocketpc/core/storage/StorageRepository.kt",
    "app/src/main/java/dev/pocketpc/core/ui/DownloadsApp.kt",
    "app/src/main/java/dev/pocketpc/core/ui/FilesApp.kt",
    "app/src/main/java/dev/pocketpc/core/storage/PocketZipArchive.kt",
    "app/src/main/java/dev/pocketpc/core/storage/PocketZipCreator.kt",
    "app/src/main/java/dev/pocketpc/core/storage/PocketGzipArchive.kt",
    "app/src/main/java/dev/pocketpc/core/storage/PocketTarArchive.kt",
    "app/src/main/java/dev/pocketpc/core/storage/PocketTextFileStore.kt",
    "app/src/main/java/dev/pocketpc/core/ui/PocketTextEditorPane.kt",
    "app/src/main/java/dev/pocketpc/core/ui/PocketImageViewerPane.kt",
    "app/src/main/java/dev/pocketpc/core/ui/PocketPdfViewerPane.kt",
    "app/src/main/java/dev/pocketpc/core/ui/PocketAudioPlayerPane.kt",
    "app/src/main/java/dev/pocketpc/core/ui/PocketVideoPlayerPane.kt",
    "app/src/main/java/dev/pocketpc/core/ui/PocketWebDocumentPane.kt",
    "app/src/main/java/dev/pocketpc/core/ui/PocketOfficePreviewPane.kt",
    "app/src/main/java/dev/pocketpc/core/ui/PocketFileQuickActions.kt",
    "app/src/main/java/dev/pocketpc/core/ui/PocketGzipArchivePane.kt",
    "app/src/main/java/dev/pocketpc/core/ui/PocketTarArchivePane.kt",
    "app/src/main/java/dev/pocketpc/core/ui/PocketIsoInspectorPane.kt",
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
for required in (
    "PocketFileHandlerReadiness.IMPLEMENTED_INTERNAL",
    "IMPLEMENTED_ARCHIVE_EXTENSIONS",
    'setOf("zip", "tar", "gz", "tgz")',
    'if (extension == "iso")',
    "PocketFileHandler.PDF_VIEWER",
    "IMPLEMENTED_IMAGE_EXTENSIONS",
    "IMPLEMENTED_AUDIO_EXTENSIONS",
    "IMPLEMENTED_VIDEO_EXTENSIONS",
    "IMPLEMENTED_OFFICE_PREVIEW_EXTENSIONS",
):
    if required not in associations:
        errors.append(f"PocketFileAssociations lost readiness invariant: {required}")
for modern_office in ("docx", "odt", "xlsx", "ods", "pptx", "odp"):
    if f'"{modern_office}"' not in associations:
        errors.append(f"PocketFileAssociations lost modern Office type: {modern_office}")

router = read("app/src/main/java/dev/pocketpc/core/storage/PocketFileRouter.kt")
if '"tgz"' not in router:
    errors.append("PocketFileRouter lost TGZ archive routing")

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

zip_pane = read("app/src/main/java/dev/pocketpc/core/ui/PocketZipArchivePane.kt")
for required in (
    "inspectPocketZip(context, uri)",
    "extractPocketZipToDownloads(",
    '"Extrair em P:\\\\Downloads"',
):
    if required not in zip_pane:
        errors.append(f"PocketZipArchivePane missing internal ZIP flow: {required}")

gzip_engine = read("app/src/main/java/dev/pocketpc/core/storage/PocketGzipArchive.kt")
for required in (
    "GZIPInputStream(",
    "MAX_GZIP_EXTRACTED_BYTES",
    '".pocketpc-part-${System.nanoTime()}-$finalName"',
    "runCatching { target?.delete() }",
    "pocketGzipOutputName(",
):
    if required not in gzip_engine:
        errors.append(f"PocketGzipArchive lost safety invariant: {required}")

gzip_pane = read("app/src/main/java/dev/pocketpc/core/ui/PocketGzipArchivePane.kt")
for required in (
    "extractPocketGzipToDownloads(",
    '"Extrair em P:\\\\Downloads"',
):
    if required not in gzip_pane:
        errors.append(f"PocketGzipArchivePane missing internal GZIP flow: {required}")

tar_engine = read("app/src/main/java/dev/pocketpc/core/storage/PocketTarArchive.kt")
for required in (
    "validateTarChecksum(block)",
    "validatePocketTarEntryPath(header.path)",
    "MAX_TAR_ENTRY_COUNT",
    "MAX_TAR_PATH_DEPTH",
    "MAX_TAR_FILE_BYTES",
    "MAX_TAR_TOTAL_BYTES",
    'Regex("^[A-Za-z]:")',
    'segment != "." && segment != ".."',
    "WINDOWS_RESERVED_TAR_NAMES",
    "runCatching { extractionRoot?.delete() }",
):
    if required not in tar_engine:
        errors.append(f"PocketTarArchive lost safety invariant: {required}")

tar_pane = read("app/src/main/java/dev/pocketpc/core/ui/PocketTarArchivePane.kt")
for required in (
    "inspectPocketTar(context, uri)",
    "extractPocketTarToDownloads(",
    '"Extrair em P:\\\\Downloads"',
):
    if required not in tar_pane:
        errors.append(f"PocketTarArchivePane missing internal TAR flow: {required}")

iso = read("app/src/main/java/dev/pocketpc/core/ui/PocketIsoInspectorPane.kt")
for required in (
    "ISO_DESCRIPTOR_START_SECTOR",
    'signature == "CD001"',
    "parsePocketIso9660PrimaryDescriptor(pvd)",
    "openFileDescriptor(Uri.parse(uriString), \"r\")",
    "Primary Volume Descriptor ISO9660 não encontrado",
):
    if required not in iso:
        errors.append(f"PocketIsoInspectorPane lost ISO9660 invariant: {required}")

iso_engine = read("app/src/main/java/dev/pocketpc/core/storage/PocketIso9660.kt")
for required in (
    "readUInt32LittleEndian(descriptor, 80)",
    "readUInt32BigEndian(descriptor, 84)",
    "require(volumeBlocksLittle == volumeBlocksBig)",
    "readUInt16LittleEndian(descriptor, 128)",
    "readUInt16BigEndian(descriptor, 130)",
    "require(logicalBlockLittle == logicalBlockBig)",
):
    if required not in iso_engine:
        errors.append(f"PocketIso9660 lost both-endian invariant: {required}")

zip_creator = read("app/src/main/java/dev/pocketpc/core/storage/PocketZipCreator.kt")
for required in (
    "MAX_CREATE_ZIP_ENTRY_COUNT",
    "MAX_CREATE_ZIP_DEPTH",
    "MAX_CREATE_ZIP_FILE_BYTES",
    "MAX_CREATE_ZIP_TOTAL_BYTES",
    '".pocketpc-part-${System.nanoTime()}-$outputName"',
    "runCatching { target?.delete() }",
    "ZipOutputStream(raw)",
    "createPocketZipFileInDownloads(",
    "createPocketZipBesideEntry(",
):
    if required not in zip_creator:
        errors.append(f"PocketZipCreator lost transactional/safety invariant: {required}")

quick_actions = read("app/src/main/java/dev/pocketpc/core/ui/PocketFileQuickActions.kt")
for required in (
    "createPocketZipFileInDownloads(",
    '"Compactar em P:\\\\Downloads"',
    "planPocketFileOpenAsText(",
    '"Abrir como texto no PocketPC"',
):
    if required not in quick_actions:
        errors.append(f"PocketFileQuickActions lost internal action: {required}")

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
    'Text("Reverter")',
):
    if required not in text_editor:
        errors.append(f"PocketTextEditorPane missing editable-text behavior: {required}")

image_viewer = read("app/src/main/java/dev/pocketpc/core/ui/PocketImageViewerPane.kt")
for required in (
    "MAX_IMAGE_PREVIEW_SIDE",
    "MIN_IMAGE_ZOOM",
    "MAX_IMAGE_ZOOM",
    ".graphicsLayer",
    "rotationZ = rotation",
):
    if required not in image_viewer:
        errors.append(f"PocketImageViewerPane missing internal image behavior: {required}")

pdf_viewer = read("app/src/main/java/dev/pocketpc/core/ui/PocketPdfViewerPane.kt")
for required in (
    "PdfRenderer(descriptor)",
    "PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY",
    "openFileDescriptor(Uri.parse(uriString), \"r\")",
    "Página ${state.pageIndex + 1} de ${state.pageCount}",
):
    if required not in pdf_viewer:
        errors.append(f"PocketPdfViewerPane missing internal PDF flow: {required}")

audio_player = read("app/src/main/java/dev/pocketpc/core/ui/PocketAudioPlayerPane.kt")
for required in (
    "MediaPlayer()",
    "setDataSource(",
    "prepareAsync()",
    'Text(if (playing) "Pausar" else "Reproduzir")',
):
    if required not in audio_player:
        errors.append(f"PocketAudioPlayerPane missing internal playback behavior: {required}")

video_player = read("app/src/main/java/dev/pocketpc/core/ui/PocketVideoPlayerPane.kt")
for required in (
    "VideoView(context)",
    "AndroidView(",
    "setVideoURI(Uri.parse(uriString))",
    "stopPlayback()",
):
    if required not in video_player:
        errors.append(f"PocketVideoPlayerPane missing internal video behavior: {required}")

web_document = read("app/src/main/java/dev/pocketpc/core/ui/PocketWebDocumentPane.kt")
for required in (
    "settings.javaScriptEnabled = false",
    "settings.domStorageEnabled = false",
    "settings.allowFileAccess = false",
    "settings.allowContentAccess = false",
    "settings.blockNetworkLoads = true",
    "shouldOverrideUrlLoading(",
    "loadDataWithBaseURL(",
):
    if required not in web_document:
        errors.append(f"PocketWebDocumentPane lost isolation invariant: {required}")

office = read("app/src/main/java/dev/pocketpc/core/ui/PocketOfficePreviewPane.kt")
for required in (
    "MAX_OFFICE_CONTAINER_BYTES",
    "MAX_OFFICE_XML_BYTES",
    "MAX_OFFICE_ENTRY_COUNT",
    "MAX_OFFICE_TOTAL_XML_BYTES",
    "ZipFile(cacheFile)",
    "XmlPullParser.FEATURE_PROCESS_DOCDECL",
    "XmlPullParser.DOCDECL",
    '"word/document.xml"',
    '"xl/sharedStrings.xml"',
    'Regex("^ppt/slides/slide([0-9]+)[.]xml$")',
    'setOf("docx", "odt", "xlsx", "ods", "pptx", "odp")',
):
    if required not in office:
        errors.append(f"PocketOfficePreviewPane lost safe-preview invariant: {required}")

open_overlay = read("app/src/main/java/dev/pocketpc/core/ui/PocketFileOpenOverlay.kt")
for required in (
    "PocketTextEditorPane(",
    "PocketImageViewerPane(request = currentRequest)",
    "PocketZipArchivePane(request = currentRequest)",
    "PocketGzipArchivePane(request = currentRequest)",
    "PocketTarArchivePane(request = currentRequest)",
    "PocketIsoInspectorPane(request = currentRequest)",
    "PocketPdfViewerPane(request = currentRequest)",
    "PocketOfficePreviewPane(request = currentRequest)",
    "PocketWebDocumentPane(request = currentRequest)",
    "PocketVideoPlayerPane(request = currentRequest)",
    "PocketAudioPlayerPane(request = currentRequest)",
    "PocketFileQuickActions(request = currentRequest)",
):
    if open_overlay.count(required) != 1:
        errors.append(f"Global file-open overlay must mount exactly one: {required}")

if 'extension == "tar"' not in open_overlay:
    errors.append("Global overlay lost TAR dispatch")
if 'extension == "gz" || extension == "tgz"' not in open_overlay:
    errors.append("Global overlay lost GZIP/TGZ dispatch")
if 'extension == "iso"' not in open_overlay:
    errors.append("Global overlay lost ISO dispatch")

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
print("pocket_gzip_security=guarded")
print("pocket_tar_security=guarded")
print("pocket_iso_inspector=guarded")
print("pocket_zip_creation=guarded")
print("pocket_text_editor=guarded")
print("pocket_image_viewer=guarded")
print("pocket_pdf_viewer=guarded")
print("pocket_audio_player=guarded")
print("pocket_video_player=guarded")
print("pocket_web_document=isolated")
print("pocket_office_preview=guarded")
