# PocketPC — 0.1.0-alpha12

PocketPC is an experimental Android desktop/runtime project with strict evidence labels.

## Alpha 12 — reproducible local Windows build

GitHub-hosted Actions is still failing before step 1, so Alpha 12 adds an independent Windows build path without weakening the verification gates.

The local builder:

- reads toolchains/android-build-lock.json;
- refuses a dirty Git tree by default;
- embeds the exact clean Git commit, or LOCAL_UNPINNED only when dirty builds are explicitly allowed;
- requires the pinned JDK major;
- discovers the Android SDK and validates required components;
- can install missing SDK components through sdkmanager;
- downloads the pinned Gradle binary distribution;
- verifies its SHA-256 before extraction;
- runs Python policy checks when Python 3 is available, with an optional strict requirement;
- runs JVM unit tests, Android lint and assembleDebug;
- inspects the APK structure;
- rejects unapproved PRoot/talloc/shmem payload;
- verifies APK signing through apksigner;
- records APK SHA-256 and a structured local-build-record.json.

## Current build lock

- app: dev.pocketpc.core 0.1.0-alpha12 / code 12
- AGP: 9.4.0
- Kotlin Compose plugin: 2.3.21
- Gradle: 9.6.0
- JDK: 17
- compileSdk: 37
- Build Tools: 36.0.0
- NDK: 29.0.14206865
- CMake: 3.22.1

Gradle 9.6.0 binary ZIP SHA-256:

bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01

## Windows build

Basic:

powershell -ExecutionPolicy Bypass -File .\scripts\build-local-windows.ps1

Strict policy mode:

powershell -ExecutionPolicy Bypass -File .\scripts\build-local-windows.ps1 -RequirePythonPolicyChecks

Install missing Android SDK components:

powershell -ExecutionPolicy Bypass -File .\scripts\build-local-windows.ps1 -InstallMissingSdkComponents -AcceptAndroidLicenses

## Output

Default output is under ignored local-build/ and contains:

- PocketPC APK;
- APK SHA-256 sidecar;
- apk-signing.txt;
- local-build-record.json.

Verify the result with:

python scripts/verify-local-build-record.py local-build/<build-dir>

An exact source revision can be required with --expected-commit <40-char-sha>.

## Evidence chain

Git commit -> local build record -> APK SHA-256/signing identity -> installed Build Identity -> Device Evidence -> exported Evidence Bundle.

PRoot remains unbundled and unapproved. Linux execution remains disabled.
