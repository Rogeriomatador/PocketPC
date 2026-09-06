# PocketPC — 0.1.0-alpha16

PocketPC is an experimental Android desktop/runtime project with strict evidence labels.

## Alpha 16 — Windows Preflight Doctor

Alpha 16 adds a non-destructive preflight stage before the one-command physical test.

The doctor checks:

- toolchain lock;
- Git availability and clean tree;
- Python 3;
- required JDK major;
- Android SDK;
- every locked Android SDK component;
- adb availability;
- Gradle cache hash when present;
- free disk space;
- ADB authorization state;
- exactly one physical device or an explicit DeviceSerial;
- arm64-v8a;
- Android API >= minSdk;
- basic /data free space;
- optional HTTPS connectivity to Gradle/Google hosts.

The raw ADB serial is never written to the report; only SHA-256(serial) is persisted.

## Two-pass preflight

first-physical-test-windows.ps1 now runs the doctor twice:

1. before build, optionally allowing missing SDK components as repairable WARN when -InstallMissingSdkComponents is requested;
2. after build, requiring the environment/device to be fully ready before physical validation.

Each preflight produces JSON + SHA-256 sidecar.

## Verification

verify-preflight-record.py checks PASS/WARN/FAIL counts, classification, required checks, device identity hash and sidecar.

test-preflight-record-verifier.py provides deterministic good/bad fixtures.

The final physical success token remains POCKETPC_FIRST_PHYSICAL_TEST_OK and still requires all Alpha 15 gates.
