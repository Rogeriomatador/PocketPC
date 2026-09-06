# PocketPC architecture — draft 0.15

## First physical test chain

clean Git -> strict build -> local build record -> APK -> physical install -> install record -> debug evidence runner -> evidence bundle -> cross-verifier -> physical validation record -> final first-physical-test record.

Every record is hash-linked and independently verified.

## Separate execution branch

PRoot source/artifact/license/device approval and Linux executor enablement remain independent later gates.
