# CI status — Alpha 7

## Current evidence

At commit 35b814f45818b75d7a16651da3f21d7dc468eb9a:

- Android CI run #16: failure before steps;
- PRoot Source Audit run #1: failure before steps;
- both jobs expose steps=null;
- both jobs expose logs_url=null.

No checkout, Python, Gradle, NDK or tests ran.

Classification: **CI RUNNER/ACCOUNT/INFRA UNRESOLVED**.

## Alpha 7 Android test scope

When the runner starts, the existing unit-test task will include:

- TAR security tests;
- manifest v2 tests;
- bind/environment tests;
- process supervisor tests;
- guest path resolver tests;
- symlink/hardlink preparation tests;
- interrupted link preparation recovery;
- NOFOLLOW deletion external-target preservation.

## Separate PRoot source audit

The PRoot Source Audit workflow independently downloads pinned source archives and recalculates SHA-256. It still has no execution evidence because its first run also failed before step 1.

## Evidence rules

- failure before checkout -> infrastructure only;
- unit tests pass -> CI validation for pure/JVM logic;
- APK assembles -> CI VALIDATED build;
- links work on phone -> DEVICE TESTED guest filesystem;
- PRoot command works on phone -> DEVICE TESTED Linux execution.
