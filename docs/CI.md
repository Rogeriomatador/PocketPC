# CI status and recovery

## Current classification

Runs observed through Alpha 4 predecessors fail before the first declared step begins. Jobs report steps: null and no logs. No checkout, Java, SDK, NDK, Gradle, Kotlin, CMake or compiler action is reached.

Classification: **CI RUNNER/ACCOUNT/INFRA UNRESOLVED**.

## Alpha 4 workflow intends to test

1. checkout;
2. Temurin JDK 17;
3. Android SDK API 37 / Build Tools 36.0.0;
4. Android NDK r29 (29.0.14206865);
5. CMake 3.22.1;
6. Gradle 9.6.0;
7. unit tests;
8. Android lint;
9. debug APK;
10. inspect APK for libpocketpc_runtime.so in arm64-v8a/x86_64;
11. upload reports/APK.

If a run fails before checkout again, do not call it a source build failure.

When the runner finally starts, the first actionable Gradle/CMake/Kotlin error becomes the next engineering target.
