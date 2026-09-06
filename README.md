# PocketPC — 0.1.0-alpha15

PocketPC is an experimental Android desktop/runtime project with strict evidence labels.

## Alpha 15 — One-command First Physical Test

Alpha 15 composes the Alpha 12–14 gates into one strict Windows entry point:

scripts/first-physical-test-windows.ps1

It refuses a dirty Git tree, runs the reproducible local builder in strict Python policy mode, validates the exact build record, installs the APK on one physical ARM64 ADB device, launches PocketPC, runs the debug-only evidence runner, pulls and verifies the evidence bundle, cross-checks build/install/device identity, requires critical filesystem PASS and Native Runtime Host loaded, writes a final physical-validation record, and verifies the final first-physical-test record.

The success token is:

POCKETPC_FIRST_PHYSICAL_TEST_OK

## Command

powershell -ExecutionPolicy Bypass -File .\scripts\first-physical-test-windows.ps1

Optional:

- -InstallMissingSdkComponents
- -AcceptAndroidLicenses
- -DeviceSerial <serial>
- -RequireInstalledApkHash

## Final evidence

The physical-validation directory contains the APK/bundle-linked evidence chain plus:

- first-physical-test-record.json
- first-physical-test-record.json.sha256
- first-physical-test-verification.txt

The final record is independently checked against the clean source commit, local build record, physical-validation record, APK SHA-256 and evidence-bundle SHA-256.

PRoot is still unbundled/unapproved and Linux execution remains disabled.
