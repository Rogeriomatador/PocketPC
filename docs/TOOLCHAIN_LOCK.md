# Android Build Toolchain Lock — Alpha 12

Status: PINNED METADATA

Source of truth: toolchains/android-build-lock.json

## Current lock

- package: dev.pocketpc.core
- app: 0.1.0-alpha12 / code 12
- AGP: 9.4.0
- Kotlin Compose plugin: 2.3.21
- Gradle: 9.6.0
- JDK: 17
- compileSdk: 37
- Build Tools: 36.0.0
- NDK: 29.0.14206865
- CMake: 3.22.1

Gradle binary distribution SHA-256:

bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01

## Drift detection

scripts/verify-android-build-lock.py compares the lock against root/app Gradle configuration, Android CI and the local Windows builder.

A mismatch fails instead of silently choosing one configuration.

The lock is a configuration/provenance lock; it is not a claim that every Android SDK package is bit-for-bit reproducible.
