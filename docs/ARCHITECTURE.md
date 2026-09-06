# PocketPC architecture — draft 0.13

## Build and physical-evidence chain

android-build-lock -> clean Git commit -> Windows/CI build -> local-build-record -> exact APK -> ADB Device Install Gate -> device-install-record -> installed PocketPC -> Device Evidence Harness -> Evidence Bundle -> full device-chain verifier.

Each transition has a distinct evidence label and does not imply the next.

## Runtime chain

Rootfs staging -> safe extraction -> guest links -> launch policy -> PRoot supply-chain quarantine -> artifact attestation -> future executor.

PRoot approval and Linux execution remain independent from the build/device-install chain.
