# CI status and recovery — Alpha 6

## Current classification

GitHub-hosted runs through Alpha 5 have failed before the first declared workflow step. Jobs report steps=null and logs_url=null.

Classification: **CI RUNNER/ACCOUNT/INFRA UNRESOLVED**.

Tracked in Issue #3.

## Alpha 6 workflow intends to test

1. checkout;
2. JDK 17;
3. Android SDK API 37 / Build Tools 36.0.0;
4. Android NDK r29;
5. CMake 3.22.1;
6. Gradle 9.6.0;
7. all unit tests;
8. Android lint;
9. debug APK;
10. packaged PocketPC native runtime host;
11. rejection of unreviewed PRoot libraries;
12. report/APK upload.

New unit-test sources cover safe TAR extraction, schema v2, bind policy, environment validation, PRoot argv planning and bounded process supervision.

## Evidence classification

- failure before checkout -> infrastructure/account;
- compiler/Gradle reached -> build evidence exists;
- APK assembled -> CI VALIDATED build only;
- physical install/run -> DEVICE TEST required.
