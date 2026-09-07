# PocketPC — 0.1.0-alpha18

PocketPC is an experimental Android desktop/runtime project with strict evidence labels.

## Alpha 18 — usable pocket desktop foundation

Alpha 18 begins the user-facing PC layer while preserving the evidence-gated physical pipeline.

Implemented in source:

- integrated PocketPC browser window;
- Google home/search and address navigation;
- back, forward, reload and home controls;
- experimental desktop-site user-agent mode;
- Android DownloadManager integration;
- direct Downloads entry point;
- WebView Safe Browsing and restricted file-scheme access;
- browser navigation unit tests;
- per-capability physical filesystem diagnostics;
- Alpha 17 physical install evidence retained as historical evidence.

The browser deliberately reuses the device WebView/Chromium implementation instead of bundling a second browser engine. This reduces APK size and memory overhead on phones.

## Evidence state entering Alpha 18

The Alpha 17 run on a physical Xiaomi device proved:

- Windows process-capture self-test: PASS;
- strict local build: PASS;
- preflight before/after build: PASS;
- authorized physical ADB device: PASS;
- APK install: PASS;
- installed APK SHA-256 equals the validated local APK: PASS;
- MainActivity launch: PASS;
- filesystem critical gate: FAIL / exact capability pending detailed rerun;
- full physical chain: INCOMPLETE.

Therefore Alpha 18 source is not classified as physically validated until a new build/run proves it.

## Normal command

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\first-physical-test-windows.ps1
```

If it succeeds, the final token remains `POCKETPC_FIRST_PHYSICAL_TEST_OK`.
If it fails, the failure-triage directory and structured evidence remain the source of truth.

PRoot remains unbundled/unapproved and Linux execution remains disabled.

## PocketPC Windows Setup

For a minimal Windows host setup without Android Studio, download:

- `dist/PocketPC-Windows-Setup.zip` and run `INSTALL.bat`; or
- `PocketPC-Setup-Windows.bat` as the one-file downloader.

The setup downloads Eclipse Temurin JDK 17 through the official Adoptium API, validates its API-provided SHA-256, downloads the pinned Android Command-line Tools package directly from Google, validates the official SHA-256, installs the Android components listed in `toolchains/android-build-lock.json`, configures user environment variables, and runs the PocketPC Doctor.

Android SDK licenses still require explicit user acceptance.
