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

## Bootstrap the missing base tooling

After the preflight reports missing Java/Gradle/aapt2/CMake/Ninja, use the repository
bootstrap instead of installing arbitrary versions manually:

```bash
bash scripts/termux-bootstrap-tooling.sh
source ~/.profile
bash scripts/termux-on-device-preflight.sh
```

The bootstrap reads the repository lock, installs Termux-native Java/build helpers,
downloads the exact locked Gradle distribution, verifies its SHA-256, and exposes it
through `~/.profile`.

It deliberately does **not** claim the Android SDK 37 or NDK host-toolchain problem is
solved. A successful base-tooling bootstrap is not an APK build.

## Bootstrap the Android SDK base

After Java/Gradle/aapt2/CMake/Ninja are available, install the architecture-independent
Android platform plus the Java payload from the locked build-tools archive:

```bash
bash scripts/termux-bootstrap-android-sdk.sh
source ~/.profile
bash scripts/termux-on-device-preflight.sh
```

The script resolves the official Google repository index, downloads the locked
platform/build-tools archives, verifies the checksums published in that index, creates
`local.properties`, and configures the Termux-native `aapt2` override.

This still does not prove the Google Linux build-tools native executables work on
ARM64. The next safe gate is a Kotlin/unit-test smoke that does not assemble an APK:

```bash
bash scripts/termux-kotlin-unit-test.sh
```

Only a final `TERMUX_KOTLIN_COMPILE_UNIT_TEST_PASS` is a software-test PASS for that
exact revision. It is not an APK/native/physical PASS.

## Useful Termux packages

Current Termux repositories provide OpenJDK and Android packaging tools such as
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


## Download a published PocketPC APK without a PC

The repository now includes a separate fail-closed helper:

```bash
bash scripts/termux-install-published.sh
```

This helper does **not** build PocketPC on the phone.

It:

1. fetches `updates/stable.json`;
2. verifies that the feed explicitly says `published=true`;
3. validates package/version/HTTPS/SHA metadata;
4. downloads the APK to Android Downloads;
5. verifies the exact SHA-256;
6. uses `termux-open` to request the Android package installer when available.

If the feed is still unpublished, the script stops with:

```text
Classification : POCKETPC_ON_DEVICE_NO_PUBLISHED_APK
```

That is the correct current result until an APK with a stable signing identity is
actually published.

When Termux is the app opening the APK, Android can request "Install unknown apps"
authorization for Termux. When PocketPC itself installs a downloaded APK from its own
PocketDrive/updater, the corresponding authorization is for PocketPC.

Neither authorization bypasses Android signature compatibility rules.

## Optional Termux bridge research

PocketPC now declares the optional Termux `RUN_COMMAND` permission and can detect:

- whether Termux is visible/installed;
- whether PocketPC has been granted `com.termux.permission.RUN_COMMAND`.

No Termux command is executed yet.

A future bridge also requires the user to set:

```text
allow-external-apps=true
```

inside `~/.termux/termux.properties`.

This remains an explicit opt-in integration, not an implicit privilege path.


## Run repository policies on the phone

For source-only validation that needs no Android SDK or NDK:

```bash
bash scripts/termux-static-check.sh
```

It runs Python syntax compilation plus the repository's platform-independent
policy/self-test scripts, including Desktop Mode, enum coverage, update feed,
CI-version, evidence-schema and PocketDrive/research guards.

A complete successful run ends with:

```text
Classification : TERMUX_STATIC_POLICY_PASS
```

This classification means only that those static repository checks passed on the
phone. It does not compile Kotlin, run Android Lint, assemble an APK or perform a
physical PocketPC test.
