# PRoot execution substrate — Alpha 8

Status: **SOURCE LOCKED / ARTIFACT QUARANTINE DEFINED / NOT BUNDLED / EXECUTOR DISABLED**

## Source baseline

- termux/termux-packages commit: 32f2b3a6c7a1f2a6d068e523d6248e6b4a334d68
- PRoot 5.1.107.92
- PRoot tag commit: 7266fb3e8516535682f5a9c8f3a7e70f6506eddb
- libandroid-shmem 0.7
- libandroid-shmem tag commit: 7f0bd7e25dbdd146265aff7c6a890029e374622d
- libtalloc 2.4.3

## Loader contract

Upstream PRoot supports PROOT_LOADER.

PocketPC's proposed ARM64 alias remains:

~~~text
PROOT_LOADER=<nativeLibraryDir>/libproot_loader.so
~~~

That alias is not approved yet.

## Artifact quarantine

Alpha 8 introduces:

- ARTIFACT_CONTRACT.json
- audit-proot-artifacts.py
- build-proot-aarch64-quarantine.sh
- test-proot-artifact-policy.py
- make-proot-artifact-candidate.py
- PRoot Quarantine Build workflow

The quarantine build cannot promote files into app/src.

## ELF policy

Required target:

- ELF64
- little endian
- AArch64

Forbidden:

- RPATH
- RUNPATH
- glibc-specific DT_NEEDED names

Unknown/non-system dependencies remain review-required.

## talloc

The runtime SONAME/filename is not assumed.

The exact build must reveal it through ELF SONAME/DT_NEEDED.

The license is also marked unresolved until the pinned source's own licensing files are audited.

## Runtime readiness

ExecutionSubstrateProbe now separates:

- files present;
- artifact contract approved.

prootReady requires both.

Artifact approval is hardcoded false in Alpha 8.

## Before approval

Required evidence:

1. source archive independent hash audit;
2. exact build reproduction;
3. ELF report;
4. dependency closure;
5. artifact SHA-256 lock;
6. final license/notice/source-distribution plan;
7. Android packaging test;
8. physical-device loader test;
9. guest filesystem device test;
10. deterministic process cleanup.

Executor enablement remains a later independent gate.
