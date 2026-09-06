# CI status — Alpha 12

Latest observed hosted Android CI still fails before checkout with steps=null and logs_url=null. This is infrastructure/account evidence, not source compilation evidence.

## Android CI intended checks

- Android build-lock verification;
- PRoot policy validation;
- ELF policy self-test;
- PRoot approval policy;
- evidence-bundle verifier self-test;
- local-build-record verifier self-test;
- PowerShell parse check for the Windows builder;
- unit tests;
- lint;
- assemble;
- APK SHA-256/build record;
- native host inspection;
- unapproved substrate rejection.

## Windows Local Build Harness

A separate Windows workflow is defined to exercise the same local builder with JDK 17, SDK components from the lock, Python strict policy mode, record verification against GITHUB_SHA and artifact upload.

The local PowerShell route can also run on a user's own Windows machine without relying on hosted Actions.
