# CI status — Alpha 9

## Current hosted-runner evidence

Android CI #18 and PRoot Source Audit #2:

- conclusion=failure;
- steps=null;
- logs_url=null.

No declared workflow step ran.

Classification: **CI RUNNER/ACCOUNT/INFRA UNRESOLVED**.

## Alpha 9 Android CI intended checks

- policy JSON syntax;
- ELF-policy self-test;
- approval policy validation;
- no PRoot binaries in app/src or third_party;
- JVM unit tests including attestation model tests;
- Android lint;
- APK assembly;
- native host packaging;
- no unapproved PRoot payload in APK.

## PRoot Source Audit

Independently re-hashes pinned source archives.

## PRoot Quarantine Build

Manual only. Builds raw ARM64 artifacts outside the repository and uploads JSON reports only.

## Evidence labels

- ELF policy self-test -> STATICALLY VALIDATED policy;
- approval script pass with approved=false -> locked-policy validation;
- source audit PASS -> SOURCE VERIFIED;
- real ELF audit PASS -> ARTIFACT STRUCTURE VALIDATED;
- packaging blockers resolved -> ANDROID PACKAGING CONTRACT REVIEWED;
- artifact lock + device hash match -> DEVICE ATTESTED;
- executor command works -> separate DEVICE TESTED Linux execution.
