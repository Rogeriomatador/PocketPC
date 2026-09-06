# Automated Physical Validation — Alpha 14

Status: IMPLEMENTED SOURCE / PHYSICAL EXECUTION PENDING

## Debug-only evidence runner

app/src/debug contains DebugEvidenceActivity and its debug manifest entry.

That component is not part of the main source set. It exists to automate development evidence collection and is not intended for release distribution.

## Why app-specific external storage

The in-app evidence engine keeps its canonical data internal. For ADB automation the debug runner copies only the completed evidence artifacts into the app-specific external files directory.

This avoids broad storage permissions while allowing adb pull to retrieve the finished ZIP without PowerShell binary-stdout conversion.

## validate-device-windows.ps1

Preconditions:

- clean/pinned local build;
- Python 3;
- Android SDK Platform Tools;
- one authorized physical ADB device;
- arm64-v8a.

The script invokes install-device-windows.ps1 first, starts DebugEvidenceActivity, waits for the result, pulls artifacts, verifies the evidence bundle and runs verify-device-chain.py.

It requires filesystem critical PASS and Native Runtime Host loaded.

Then it writes and independently verifies physical-validation-record.json.

## Evidence classes

DEVICE_INSTALL_* means installation evidence only.

POCKETPC_DEVICE_CHAIN_OK means build/install/bundle identity is cross-consistent.

PHYSICAL_DEVICE_CHAIN_VERIFIED means the automated chain also confirmed the critical filesystem self-test and the native host load.

None of those labels imply PRoot approval or Linux execution.
