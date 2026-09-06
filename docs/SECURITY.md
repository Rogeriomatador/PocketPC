# Security model — Alpha 8

## Archive and guest-filesystem boundary

Alpha 7 protections remain:

- strict archive validation;
- link-free extraction;
- transactional link preparation;
- hardlink cycle/inode checks;
- guest-aware symlink resolution;
- NOFOLLOW cleanup.

## Supply-chain boundary

Alpha 8 adds:

- exact source recipe pin;
- source archive audit script;
- explicit ELF artifact contract;
- build quarantine outside app/src;
- SHA-256 capture;
- AArch64/ELF checks;
- DT_NEEDED/SONAME capture;
- RPATH/RUNPATH rejection;
- glibc dependency rejection;
- unresolved dependency review;
- unresolved talloc-license gate;
- review-only candidate lock;
- no automatic promotion.

## Runtime boundary

File presence is insufficient.

ExecutionSubstrateProbe requires an artifact approval flag in addition to component presence.

Alpha 8 keeps that approval false.

The Linux executor also remains disabled independently.

## CI boundary

Android CI rejects PRoot/talloc/shmem binaries from source directories and from the final APK until approval.

A separate manual quarantine workflow may build third-party artifacts only outside the repository tree and uploads reports only.

## Remaining risks

- no real PRoot artifact audit has passed;
- no source audit has run due hosted runner failure;
- no license package has been reviewed;
- no device test exists;
- process tree behavior remains unknown;
- executor is not enabled.
