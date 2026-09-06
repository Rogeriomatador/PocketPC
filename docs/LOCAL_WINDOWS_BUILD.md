# Local Windows Build Harness — Alpha 12

Status: IMPLEMENTED SOURCE / NOT EXECUTED IN THIS SESSION

## Purpose

GitHub-hosted jobs currently fail before checkout. Alpha 12 defines a local Windows build path with the same declared toolchain and evidence gates.

## Toolchain source of truth

scripts/build-local-windows.ps1 reads toolchains/android-build-lock.json.

It does not auto-select newer Gradle, SDK, NDK or CMake versions.

## Clean-tree rule

Default behavior: a dirty Git tree aborts the build.

With -AllowDirtyTree, the current HEAD is still recorded but the APK embeds LOCAL_UNPINNED and the result is classified LOCAL_BUILD_DIRTY_UNPINNED.

## Java and Android SDK

Java lookup order: explicit -JavaHome, JAVA_HOME, Android Studio JBR in Program Files, Android Studio JBR in LocalAppData.

Android SDK lookup order: explicit -AndroidSdkRoot, ANDROID_SDK_ROOT, ANDROID_HOME, %LOCALAPPDATA%\Android\Sdk.

Missing SDK components fail closed unless -InstallMissingSdkComponents is supplied.

## Gradle bootstrap

The builder downloads the pinned Gradle binary distribution and verifies the lock SHA-256 before extracting it.

## Policy checks

If functional Python 3 is available, the builder runs:

- verify-android-build-lock.py
- test-proot-artifact-policy.py
- verify-proot-approval.py
- test-device-evidence-bundle-verifier.py
- test-local-build-record-verifier.py

Without Python, the build is classified as LOCAL_BUILD_PYTHON_POLICY_SKIPPED unless -RequirePythonPolicyChecks is used, in which case the build aborts.

## Android build gates

The builder runs :app:testDebugUnitTest, :app:lintDebug and :app:assembleDebug sequentially.

## APK inspection

Required APK entries include the PocketPC native runtime host and policy assets. Unapproved PRoot/talloc/shmem payload is rejected.

apksigner verifies the APK and supplies signing-certificate SHA-256 values.

## Output

The output directory contains the APK, APK SHA-256 sidecar, apk-signing.txt and local-build-record.json.

verify-local-build-record.py independently recomputes and checks those relationships.

A successful run is local/software build evidence, not DEVICE TESTED evidence.
