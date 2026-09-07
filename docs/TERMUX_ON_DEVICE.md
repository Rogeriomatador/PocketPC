# PocketPC on-device work with Termux

## Goal

Use the phone itself to inspect whether the current PocketPC repository can be
validated or built without relying on the Windows development machine.

This is deliberately fail-closed. A phone having Java and Gradle does not mean the
full PocketPC APK can be built or can update the already-installed package.

## Why the full build is harder

PocketPC currently uses:

- Java 17;
- Gradle 9.6.0;
- Android Gradle Plugin 9.4.0;
- compileSdk 37;
- native CMake code;
- NDK 29.0.14206865.

Termux provides native ARM64 tooling such as OpenJDK and aapt2, so static/unit/UI-only
Android work is technically promising.

The current full application also requires an Android NDK host toolchain that can
actually execute on the phone. Google's normal Linux NDK package is primarily aimed at
desktop Linux hosts. The preflight must therefore prove that the required NDK clang is
executable instead of assuming it is.

## Run the diagnostic

From a cloned PocketPC repository:

```bash
bash scripts/termux-on-device-preflight.sh
```

The command does not install packages, build an APK, sign an APK or modify the installed
PocketPC application.

Possible classifications include:

- TERMUX_TOOLING_INCOMPLETE
- TERMUX_STATIC_TEST_READY
- TERMUX_JAVA_UI_CANDIDATE_NATIVE_BLOCKED
- TERMUX_FULL_BUILD_CANDIDATE_NOT_EXECUTED

A candidate classification is not a PASS.

## Useful Termux packages

Current Termux repositories provide OpenJDK 17 and Android packaging tools such as
aapt2. A typical research environment can therefore include tools such as:

```text
git
python
openjdk-17
aapt2
cmake
ninja
clang
```

Do not install a large Android/NDK toolchain only because this document lists it.
Run the preflight and use its missing-tool output first.

## Signing is a separate gate

Even if Termux successfully generates an APK, Android only permits an in-place update
of `dev.pocketpc.core` when the new APK is signed by a compatible signing identity.

A newly generated Termux debug keystore will normally differ from the key that signed
the PocketPC APK currently installed from the Windows build.

Therefore:

- build success != update success;
- APK install permission != signature compatibility;
- unknown-source authorization does not bypass Android signature checks.

The long-term PocketPC update flow requires a stable signing identity kept outside the
public repository.

## Recommended development path

1. Keep P: PocketDrive independent of APK lifecycle.
2. Establish a stable PocketPC signing identity.
3. Store the signing key only in secure local/CI secret storage.
4. Build/publish a signed APK.
5. Activate `updates/stable.json` with exact HTTPS URL, SHA-256 and source revision.
6. Let the installed PocketPC check, download, verify and request the Android update.

After the updater bootstrap is installed once, routine future updates can be initiated
inside PocketPC without ADB or PowerShell. Android may still require the user to approve
the final install/update prompt on an ordinary sideloaded device.

## UI-only on-device build research

A future optimization can split the native runtime host from the Android UI packaging
step. If native libraries are pinned, hash-verified and unchanged, a Termux-specific
packaging mode could potentially rebuild Kotlin/Compose-only changes without running
the complete NDK build on the phone.

That mode is DESIGN until implemented and validated.
