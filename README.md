# PocketPC — 0.1.0-alpha14

PocketPC is an experimental Android desktop/runtime project with strict evidence labels.

## Alpha 14 — Automated Physical Evidence Runner

Alpha 14 removes most manual interaction from the first physical-device validation.

A debug-only Activity lives under src/debug and therefore is included only in the debug build variant. Android build-type source sets are designed for code/manifest entries that exist only in that variant. The runner can be launched by ADB with am start -W. 

The automated runner:

- collects Native Runtime Host and substrate state;
- runs the filesystem evidence probe;
- creates Device Evidence JSON;
- creates the signed-identity/policy Evidence Bundle;
- copies the final bundle to app-specific external storage;
- writes automation-result.json with the expected commit and hashes;
- never enables PRoot or the Linux executor.

## One-device physical validation flow

scripts/validate-device-windows.ps1 performs:

1. re-verify the clean local build;
2. Device Install Gate;
3. start DebugEvidenceActivity through ADB;
4. wait for automation-result.json;
5. pull the bundle/evidence with adb pull;
6. verify bundle SHA-256;
7. run the full build/install/evidence cross-verifier;
8. require filesystem critical PASS;
9. require Native Runtime Host loaded;
10. write physical-validation-record.json;
11. independently verify that final record.

Only after every step passes does it emit:

PHYSICAL_DEVICE_CHAIN_VERIFIED

## Strong physical gate

A chain can be internally consistent and still contain a runtime failure. Therefore Alpha 14 does not award the strong physical classification unless both are true:

- filesystem.allCriticalPassed = true
- nativeHost.loaded = true

## Final output

physical-validation/ contains:

- automation-result.json
- device-evidence.json
- pocketpc-evidence-bundle.zip
- bundle SHA-256 sidecar
- bundle-verification.txt
- device-chain-verification.txt
- physical-validation-record.json
- physical-validation-record.json.sha256
- physical-validation-verification.txt

PRoot remains unbundled/unapproved; prootReady is still expected false and Linux execution stays disabled.
