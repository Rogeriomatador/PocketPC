# PocketPC — 0.1.0-alpha17

PocketPC is an experimental Android desktop/runtime project with strict evidence labels.

## Alpha 17 — Automatic Failure Triage

Alpha 17 keeps the Alpha 16 one-command physical test but wraps it with automatic failure diagnostics.

scripts/first-physical-test-windows.ps1 is now the public wrapper.

The previous Alpha 16 pipeline is preserved as:

scripts/first-physical-test-core-windows.ps1

If any stage throws, the wrapper preserves the original failure and invokes:

scripts/collect-failure-triage-windows.ps1

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
- activity state filtered for dev.pocketpc.core;
- manufacturer/model/API/ABI/fingerprint;
- SHA-256 of the ADB serial instead of the raw serial;
- SHA-256 for every captured file.

triage-record.json and its sidecar make the diagnostic pack independently verifiable.

## Normal command

powershell -ExecutionPolicy Bypass -File .\scripts\first-physical-test-windows.ps1

If it succeeds, the final token remains POCKETPC_FIRST_PHYSICAL_TEST_OK.

If it fails, a failure-triage directory is created automatically when possible.

PRoot remains unbundled/unapproved and Linux execution remains disabled.
