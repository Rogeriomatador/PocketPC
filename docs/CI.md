# CI status and recovery

## Observed historical state

Actions runs before Alpha 3 were created but completed as failure before the first declared step began. No checkout, Java setup, Android SDK setup, Gradle task or Kotlin compilation was reached.

Classification: **CI RUNNER/ACCOUNT/INFRA UNRESOLVED**.

Do not edit Android source solely to fix a run that never reached the source tree.

## Alpha 3 workflow

The workflow requests:

1. checkout;
2. Temurin JDK 17;
3. Android SDK;
4. platform-tools, API 37 and Build Tools 36.0.0;
5. Gradle 9.6.0;
6. toolchain diagnostics;
7. testDebugUnitTest;
8. lintDebug;
9. assembleDebug;
10. reports and APK artifacts.

## If a run still fails before checkout

Inspect repository/account-side Actions conditions: whether Actions are enabled, hosted runners are available, and whether private-repository plan/spending/billing or account policy prevents runner allocation.

The exact cause must come from GitHub evidence; do not guess.

## If Gradle starts and fails

Reclassify as **BUILD FAILURE**, capture the first actionable Gradle/compiler/dependency error and fix that exact issue.
