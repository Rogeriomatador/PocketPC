# Android Build Toolchain Lock — Alpha 17

Current app identity: dev.pocketpc.core 0.1.0-alpha17 / code 17.

Toolchain pins remain AGP 9.4.0, Kotlin Compose 2.3.21, Gradle 9.6.0, JDK 17, compileSdk 37, Build Tools 36.0.0, NDK 29.0.14206865 and CMake 3.22.1.

Gradle binary ZIP SHA-256 remains bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01.


## Android 17 platform package naming

PocketPC keeps `compileSdk = 37`, but the Android SDK repository package is explicitly pinned as
`platforms;android-37.0`.

Starting with the Android 17 SDK line, the package identifier can include a minor API suffix and must
not be derived by blindly concatenating `platforms;android-` with the integer `compileSdk`.
The exact SDK repository identifier therefore lives in `android.platformPackage` inside
`toolchains/android-build-lock.json`, and `requiredComponents` must contain that exact value.
