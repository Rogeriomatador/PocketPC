# Android Build Toolchain Lock — Alpha 13

Status: PINNED METADATA

Source of truth: toolchains/android-build-lock.json

Current app identity: dev.pocketpc.core 0.1.0-alpha13 / code 13.

Toolchain pins remain:

- AGP 9.4.0
- Kotlin Compose plugin 2.3.21
- Gradle 9.6.0
- JDK 17
- compileSdk 37
- Build Tools 36.0.0
- NDK 29.0.14206865
- CMake 3.22.1

Gradle distribution SHA-256 remains bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01.

verify-android-build-lock.py detects drift against Gradle configuration, Android CI and the Windows local builder.
