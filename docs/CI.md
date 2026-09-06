# CI status — Alpha 10

## Current hosted runner state

Recent Android CI and PRoot Source Audit jobs continue to fail before their first declared step with steps=null and logs_url=null.

Classification remains CI RUNNER/ACCOUNT/INFRA UNRESOLVED.

## Alpha 10 intended JVM/CI checks

- PRoot policy JSON validation;
- ELF policy self-test;
- approval policy validation;
- no unapproved substrate binaries;
- guest filesystem unit tests;
- runtime attestation unit tests;
- FilesystemEvidenceProbe host test;
- Android lint;
- APK assembly;
- APK payload inspection.

## Device Evidence Harness

CI cannot substitute for this gate.

The in-app harness must be run on a physical Android device to answer device filesystem questions.

A host FilesystemEvidenceProbeTest only validates the probe logic on the CI/host filesystem.
