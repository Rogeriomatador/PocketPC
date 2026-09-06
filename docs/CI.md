# CI status and recovery

## Current classification

GitHub-hosted runs observed so far fail before their first declared workflow step. Jobs report no steps/logs.

Classification: **CI RUNNER/ACCOUNT/INFRA UNRESOLVED**.

Tracked in Issue #3.

## Alpha 5 workflow intends to test

1. checkout;
2. JDK 17;
3. Android SDK API 37 / Build Tools 36.0.0;
4. Android NDK r29;
5. CMake 3.22.1;
6. Gradle 9.6.0;
7. all unit tests, including archive security tests;
8. Android lint;
9. debug APK assembly;
10. verify PocketPC native runtime host is packaged;
11. reject unreviewed PRoot/loader/dependency libraries;
12. upload reports/APK.

## Classification rule

- failure before checkout: CI infrastructure/account, not source;
- Gradle/CMake/compiler reached: source/build failure;
- APK assembled: CI VALIDATED build only;
- APK installed/launched: requires DEVICE TEST evidence.
