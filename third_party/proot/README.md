# PocketPC PRoot supply-chain and approval policy

Status: **SOURCE_METADATA_LOCKED / ARTIFACT_AUDIT_REQUIRED / RUNTIME_APPROVAL=false**

## Source authority

Pinned Termux recipe:

- termux/termux-packages
- commit 32f2b3a6c7a1f2a6d068e523d6248e6b4a334d68

Pinned component baseline:

- PRoot 5.1.107.92
- libandroid-shmem 0.7
- libtalloc 2.4.3

## License boundary

talloc remains UNRESOLVED_SOURCE_AUDIT_REQUIRED because recipe/distribution metadata conflict. The pinned source's licensing files are the required authority before redistribution.

## Artifact quarantine

ARTIFACT_CONTRACT.json defines:

- AArch64 ELF policy;
- dependency audit;
- RPATH/RUNPATH rejection;
- Android packaging constraints.

Raw build outputs never become approved merely by passing ELF checks.

## Android packaging boundary

Android's native library path convention requires lib<name>.so aliases.

A raw versioned talloc artifact such as libtalloc.so.2 is not treated as an approved packaging name.

The exact build must either:

- be rebuilt with an audited Android-compatible dynamic-link contract; or
- remove the dynamic talloc dependency through an audited alternative.

Renaming without resolving DT_NEEDED is not sufficient.

## Final artifact lock

A future approved substrate requires:

~~~text
third_party/proot/ARTIFACTS.lock.json
~~~

That file does not exist yet.

It must contain reviewed artifact/dependency/packaging evidence and promotion.approved=true before the embedded runtime approval may change.

## Runtime policy assets

Gradle packages third_party/ as APK assets for attestation policy binding.

A future approval manifest must contain the SHA-256 of:

- proot/LOCK.json
- proot/ARTIFACT_CONTRACT.json
- proot/ARTIFACTS.lock.json

## Current runtime approval

~~~text
approved=false
status=NOT_APPROVED
~~~

No PRoot binary is committed or approved.
