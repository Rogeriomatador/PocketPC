# PocketPC architecture — draft 0.12

## Runtime path

Android host -> desktop shell -> runtime package pipeline -> guest filesystem -> execution policy -> PRoot supply-chain quarantine -> runtime attestation -> physical device evidence -> evidence bundle.

## Build-evidence path — Alpha 12

android-build-lock.json -> clean Git commit -> local Windows builder or CI -> policy checks -> unit tests/lint/assemble -> APK structure/signing inspection -> APK SHA-256 -> local-build-record.json -> installed Build Identity -> Device Evidence Bundle.

The local builder and hosted CI are independent producers targeting the same declared toolchain.

Neither path can approve PRoot or enable Linux execution.

The reviewed PRoot executor, PTY/Linux graphics and vGPU remain later layers.
