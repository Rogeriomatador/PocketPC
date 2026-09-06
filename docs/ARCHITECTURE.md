# PocketPC architecture — draft 0.14

## Build/physical path

toolchain lock -> clean build -> local-build-record -> APK -> device install -> device-install-record -> debug evidence runner -> evidence bundle -> cross-verifier -> physical-validation-record -> final verifier.

## Strong runtime-smoke gate

The final physical classification additionally requires:

- critical filesystem self-test PASS;
- PocketPC Native Runtime Host loaded.

PRoot attestation/execution remains a separate branch and is not unlocked by this path.
