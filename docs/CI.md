# CI status — Alpha 11

## Current infrastructure evidence

GitHub-hosted jobs remain unresolved before step 1 in the latest observed runs.

This is not Android source/build evidence.

## Intended Alpha 11 Android CI

When a runner starts, the workflow is configured to:

1. checkout exact commit;
2. set up JDK/SDK/NDK/CMake/Gradle;
3. validate PRoot policy JSON;
4. run ELF policy self-test;
5. verify denied/approved PRoot approval policy;
6. run evidence-bundle verifier self-test;
7. reject unreviewed substrate binaries;
8. run JVM unit tests;
9. lint;
10. assemble APK;
11. write APK SHA-256;
12. write build record with GITHUB_SHA/version/run identity;
13. inspect native runtime host;
14. reject unapproved PRoot APK payload;
15. upload APK + SHA-256 + build record.

## Device evidence

CI cannot replace the physical-device test.

A successful APK build gives CI VALIDATED build evidence only.

Device Evidence Harness + verified exported bundle is the path toward DEVICE TEST evidence.
