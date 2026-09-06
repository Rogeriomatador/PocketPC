# Failure Triage Pack — Alpha 17

Status: IMPLEMENTED SOURCE / REAL FAILURE CAPTURE PENDING

## Goal

The first real Windows/device execution is likely to expose the first actionable compiler, SDK, ADB, package, filesystem or native-host issue.

Instead of relying on screenshots or manually copied console text, Alpha 17 captures a structured diagnostic pack automatically.

## Files

Typical failure-triage contents may include:

- triage-record.json
- triage-record.json.sha256
- preflight-before-build.json
- preflight-after-build.json
- local-build-record.json
- apk-signing.txt
- device-install-record.json
- activity-launch.txt
- automation-result.json
- device-evidence.json
- bundle-verification.txt
- device-chain-verification.txt
- physical-validation-record.json
- first-physical-test-record.json
- adb-devices.txt
- dumpsys-package.txt
- logcat-pocketpc.txt or logcat-crash-only.txt
- activity-pocketpc.txt

Only files that exist at the point of failure are copied.

## Privacy boundary

The raw ADB serial is not written to triage-record.json. Only SHA-256(serial) is persisted.

Logcat is limited to the PocketPC PID when the process exists. If the process is already gone, the fallback captures AndroidRuntime error lines only.

## Integrity

triage-record.json stores SHA-256 for every captured file.

scripts/verify-failure-triage.py recomputes those hashes and checks the record sidecar.

scripts/test-failure-triage-verifier.py verifies a good fixture and a tampered-file rejection.

## Error behavior

The wrapper never converts a failure into success just because triage succeeded.

The original first-physical-test exception is rethrown after triage collection.
