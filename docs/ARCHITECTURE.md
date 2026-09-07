# PocketPC architecture — draft 0.18

## Product layers

PocketPC is split into evidence-gated layers so a desktop-looking UI is never confused with a functioning PC runtime.

### 1. Android host desktop

Implemented source:

- Compose desktop shell;
- taskbar/start menu;
- movable/minimizable/maximizable windows;
- Files app backed by Storage Access Framework;
- local Android shell;
- telemetry/system views;
- integrated WebView browser;
- DownloadManager handoff.

### 2. Physical evidence layer

Windows host flow:

preflight -> strict build -> preflight -> install -> debug evidence -> bundle ->
cross-verification -> physical record -> final record.

A failure preserves structured triage. Alpha 18 additionally exposes each filesystem
capability result instead of collapsing them into a single boolean.

### 3. Runtime layer

Runtime staging, safe extraction, metadata and link semantics are implemented source.
Linux/PRoot execution remains separately gated and disabled until approved artifacts,
license/source provenance and physical runtime evidence exist.

## Browser architecture

The browser uses the Android System WebView/Chromium provider instead of shipping a
second browser engine. PocketPC supplies desktop-style controls, navigation policy,
Google search, optional desktop user-agent behavior and DownloadManager integration.

This is intentionally a host capability. It does not imply Linux browser execution or
Windows application compatibility.

## Evidence status

Alpha 17 physically proved APK install, installed hash equality and MainActivity launch.
Its filesystem critical gate failed, so the complete physical chain remains INCOMPLETE.
Alpha 18 source changes require a new physical run before any PHYSICAL PASS claim.
