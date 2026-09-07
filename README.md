# PocketPC — 0.1.0-alpha17

PocketPC is an experimental Android desktop/runtime project with strict evidence labels.

## Alpha 17 — Automatic Failure Triage

Alpha 17 keeps the Alpha 16 one-command physical test but wraps it with automatic failure diagnostics.

`scripts/first-physical-test-windows.ps1` is the public PowerShell entry point.

For the simplest Windows path, double-click `PocketPC-Test-Windows.bat`. It updates the repository, performs the pinned build, installs and validates the APK on the connected physical device, and leaves PocketPC open for manual testing.

The previous Alpha 16 pipeline is preserved as:

`scripts/first-physical-test-core-windows.ps1`

If any stage throws, the wrapper preserves the original failure and invokes:

`scripts/collect-failure-triage-windows.ps1`

The triage pack can capture, when available:

- failure stage/message;
- exact Git commit and dirty state;
- preflight records;
- local build record;
- APK signing report;
- device-install record;
- Activity launch output;
- automation result;
- device evidence JSON;
- bundle/device-chain verification outputs;
- physical/final validation records;
- ADB device state;
- package dumpsys;
- PocketPC-only logcat when a PID exists;
- AndroidRuntime crash-only logcat fallback;
- activity state filtered for `dev.pocketpc.core`;
- manufacturer/model/API/ABI/fingerprint;
- SHA-256 of the ADB serial instead of the raw serial;
- SHA-256 for every captured file.

`triage-record.json` and its sidecar make the diagnostic pack independently verifiable.

## Normal command

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\first-physical-test-windows.ps1
```

If it succeeds, the final token remains `POCKETPC_FIRST_PHYSICAL_TEST_OK` and the app is reopened on the phone.

If it fails, a `failure-triage` directory is created automatically when possible.

PRoot remains unbundled/unapproved and Linux execution remains disabled.

## PocketPC Windows Setup

For a minimal Windows host setup without Android Studio, download:

- `dist/PocketPC-Windows-Setup.zip` and run `INSTALL.bat`; or
- `PocketPC-Setup-Windows.bat` as the one-file downloader.

The setup downloads Eclipse Temurin JDK 17 through the official Adoptium API, validates its API-provided SHA-256, downloads the pinned Android Command-line Tools package directly from Google, validates the official SHA-256, installs the Android components listed in `toolchains/android-build-lock.json`, configures user environment variables, and runs the PocketPC Doctor.

Android SDK licenses still require explicit user acceptance.
