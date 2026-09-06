# CI status — Alpha 8

## Hosted runner status

Android CI run #17 for Alpha 7 failed before step 1:

- steps=null
- logs_url=null

PRoot Source Audit run #1 showed the same behavior.

Classification remains **CI RUNNER/ACCOUNT/INFRA UNRESOLVED**.

## Android CI Alpha 8 scope

When the runner starts, Android CI is intended to:

1. checkout;
2. validate PRoot JSON policy files;
3. run the deterministic ELF-policy self-test;
4. reject unreviewed PRoot binaries from source;
5. run JVM unit tests;
6. lint;
7. assemble APK;
8. inspect PocketPC native host;
9. reject unreviewed PRoot payload in APK;
10. upload reports/APK.

## PRoot Source Audit

Still responsible for independent source archive SHA-256 verification.

It does not build binaries.

## PRoot Quarantine Build

Manual workflow only.

It is intended to:

- checkout exact termux-packages commit;
- run source audit;
- build aarch64 PRoot/dependencies;
- extract artifacts outside repository tree;
- audit ELF metadata;
- generate a review-only candidate;
- upload JSON reports only.

A successful quarantine build is not APK approval.

## Evidence classification

- runner failure before checkout -> infrastructure only;
- ELF self-test pass -> STATICALLY VALIDATED policy;
- source audit pass -> source archives independently verified;
- quarantine artifact audit pass -> ARTIFACT STRUCTURE VALIDATED;
- Android APK build pass -> CI VALIDATED app build;
- physical phone tests -> DEVICE TESTED.
