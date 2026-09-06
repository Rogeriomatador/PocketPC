# Windows Preflight Doctor — Alpha 16

Status: IMPLEMENTED SOURCE / REAL WINDOWS EXECUTION PENDING

## Command

powershell -ExecutionPolicy Bypass -File .\scripts\doctor-windows.ps1 -RequireDevice

Optional network check:

-CheckNetwork

Optional SDK-repair-aware mode:

-AllowMissingSdkComponents

## Classifications

PREFLIGHT_PASS
PREFLIGHT_PASS_WITH_WARNINGS
PREFLIGHT_FAIL

Any FAIL causes the PowerShell doctor to throw after writing the report.

## Report

Default:

local-build/preflight/preflight-record.json

and preflight-record.json.sha256.

The record includes only the SHA-256 of the device serial.

## Integration

The one-command first physical test executes the doctor before build and again after build.

The second pass does not allow missing SDK components, preventing a partially repaired toolchain from reaching ADB installation.
