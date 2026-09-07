# Roadmap

## 0.1.0-alpha18 — usable pocket desktop foundation

### Implemented source

- integrated WebView-based browser as a PocketPC desktop app;
- Google home/search plus direct URL navigation;
- back/forward/reload/home controls;
- experimental desktop-site mode;
- DownloadManager-backed downloads;
- direct Android Downloads entry point;
- browser navigation policy tests;
- detailed filesystem capability output in the Windows physical validator;
- detailed filesystem evidence in the debug automation result.

### Physical evidence carried from Alpha 17

- strict Windows build: PASS;
- process-capture self-test: PASS;
- preflight: PASS;
- physical ADB selection: PASS;
- APK install: PASS;
- installed APK hash: PASS;
- MainActivity launch: PASS;
- relative/absolute symlink: PASS;
- hardlink: FAIL / AccessDeniedException on physical Android app-private storage;
- NOFOLLOW cleanup: PASS;
- external target preservation: PASS;
- Android host filesystem gate: PASS under the Alpha 18 split policy;
- Linux link semantics: BLOCKED until hardlink semantics are implemented without relying on denied host hardlinks;
- Alpha 17 complete physical chain: INCOMPLETE under the previous all-capabilities gate.

### Next engineering gates

1. physically validate the Alpha 18 split filesystem policy on the POCO;
2. physically validate Alpha 18 browser, downloads, Files, Terminal and window behavior;
3. design Linux hardlink semantics that do not assume Android host hardlink creation;
4. improve desktop state persistence, window resizing, keyboard/mouse behavior and multi-window workflows;
5. add a richer downloads/files experience inside PocketPC;
6. only after the host-app chain is solid, continue the separately gated Linux/PRoot execution work.

## Long-term PC direction

PocketPC should become a phone-optimized personal-computing environment rather than a visual desktop mock:

- web browsing and downloads;
- files and removable/cloud-backed storage through Android-supported APIs;
- keyboard, mouse and large-screen ergonomics;
- persistent app/window state;
- terminal and Linux ARM runtime when the execution substrate is approved;
- package/application management;
- performance/resource controls;
- future graphics acceleration research;
- clear Android sandbox/security boundaries rather than pretending to have unrestricted PC privileges.
