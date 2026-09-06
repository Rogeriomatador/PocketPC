# PocketPC — 0.1.0-alpha13

PocketPC is an experimental Android desktop/runtime project with strict evidence labels.

## Alpha 13 — Device Install & Evidence Chain

Alpha 13 extends the Alpha 12 reproducible Windows build path with a physical-device ADB installation gate.

The Windows device installer:

- re-verifies local-build-record.json before installation;
- requires functional Python 3 for that verification;
- selects one authorized ADB device or requires an explicit serial;
- refuses emulators by default;
- hashes the device serial instead of storing it raw;
- requires arm64-v8a for the first physical gate;
- installs with adb install -r -t --no-incremental;
- confirms the installed package path;
- verifies installed versionName/versionCode;
- launches dev.pocketpc.core/.MainActivity with am start -W -S;
- records launch output;
- attempts to pull the installed base APK and compare SHA-256;
- can require the installed APK hash check;
- writes device-install-record.json plus SHA-256 sidecar.

Two install classifications exist:

- DEVICE_INSTALL_APK_HASH_VERIFIED
- DEVICE_INSTALL_METADATA_VERIFIED

The second classification is used only when package/version/launch passed but Android would not allow the installed APK to be pulled for byte-hash comparison.

## Full device chain

scripts/verify-device-chain.py cross-checks:

local build record -> device install record -> exported evidence bundle

It requires the same clean source commit, app identity, APK SHA-256, signing-certificate identity and device manufacturer/model/API/ABI set.

## Windows usage

Build first:

powershell -ExecutionPolicy Bypass -File .\scripts\build-local-windows.ps1 -RequirePythonPolicyChecks

Then install the produced build directory:

powershell -ExecutionPolicy Bypass -File .\scripts\install-device-windows.ps1 -BuildDir .\local-build\<build-dir>

For the strongest install evidence add -RequireInstalledApkHash.

PRoot remains unbundled, approval remains false and Linux execution remains disabled.
