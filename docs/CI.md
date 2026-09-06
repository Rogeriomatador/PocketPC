# CI status and recovery — Alpha 6

## Android CI status

GitHub-hosted Android runs continue to fail before the first declared workflow step. Jobs report steps=null and logs_url=null.

Classification: CI RUNNER/ACCOUNT/INFRA UNRESOLVED.

Tracked in Issue #3.

## Android CI intends to test

1. checkout;
2. JDK 17;
3. Android SDK API 37 / Build Tools 36.0.0;
4. Android NDK r29;
5. CMake 3.22.1;
6. Gradle 9.6.0;
7. unit tests;
8. Android lint;
9. debug APK;
10. packaged PocketPC native runtime host;
11. rejection of unreviewed PRoot libraries;
12. report/APK upload.

## PRoot Source Audit workflow

A separate workflow is defined at .github/workflows/proot-source-audit.yml.

It triggers when the supply-chain lock/auditor changes and is designed to:

1. checkout;
2. use Python 3.13;
3. read third_party/proot/LOCK.json;
4. fetch recipes at the exact pinned termux-packages commit;
5. check recipe assertions;
6. independently download each pinned source archive;
7. recalculate SHA-256;
8. fail on any mismatch.

It does not build or bundle binaries.

Until it passes, the archive hashes retain SOURCE_METADATA_LOCKED rather than independently verified evidence.

## Evidence classification

- failure before checkout -> infrastructure/account;
- source-audit passes -> pinned source archives independently hash-verified;
- compiler/Gradle reached -> source/build evidence exists;
- APK assembled -> CI VALIDATED build only;
- physical install/run -> DEVICE TEST required.
