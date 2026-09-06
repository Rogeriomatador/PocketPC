# PocketPC — 0.1.0-alpha8

PocketPC is an experimental Android desktop/runtime project aimed at turning a phone into a desktop workstation while preserving strict evidence labels.

## Evidence ladder

- **DESIGN**
- **IMPLEMENTED**
- **STATICALLY VALIDATED**
- **CI VALIDATED**
- **DEVICE TESTED**
- **BENCHMARKED**

A higher label is never inferred from a lower one.

## Alpha 8 — artifact quarantine & ELF gate

Alpha 8 keeps all Alpha 7 guest-filesystem work and adds a fail-closed supply-chain gate for the future PRoot substrate:

- PRoot source lock remains pinned to the exact Termux recipe commit.
- talloc license metadata is now treated as unresolved until the pinned source archive is audited.
- an explicit AArch64 ELF artifact contract exists;
- produced binaries must stay in a quarantine directory;
- the auditor records SHA-256, ELF class, endianness, machine, type, DT_NEEDED, SONAME, RPATH and RUNPATH;
- glibc-style dependencies such as libc.so.6 are rejected;
- talloc runtime filename/SONAME is discovered from the exact build rather than guessed;
- a manual quarantine-build workflow is defined;
- that workflow uploads reports/candidate metadata only, not third-party binaries;
- ExecutionSubstrateProbe cannot report prootReady merely because files exist;
- an explicit artifact approval flag remains false.

Linux execution remains disabled.

## Runtime state

~~~text
STAGED_VERIFIED
  ↓
INSTALLED_DATA
  ↓
LINKS_PREPARED
  ↓
ENTRYPOINT_RESOLVED
  ↓
SOURCE_AUDIT
  ↓
ARTIFACT_ELF_AUDIT
  ↓
LICENSE_REVIEW
  ↓
DEVICE_REVIEW
  ↓
[ARTIFACT_CONTRACT_APPROVED = false]
~~~

No transition currently reaches EXECUTABLE_LINUX.

## Alpha 7 static validation retained

The guest-filesystem core passed a local Kotlin/JVM smoke:

- symlink creation;
- hardlink creation and inode verification;
- /bin/sh guest resolution through bin -> usr/bin;
- NOFOLLOW cleanup preserving an external target.

## Alpha 8 static validation

The ELF-policy self-test passed locally:

~~~text
PROOT_ELF_POLICY_SELFTEST_OK
~~~

The test accepted synthetic ELF64 little-endian AArch64 artifacts and rejected an x86_64 loader under the ARM64 contract.

This validates the policy implementation only. It does not validate real PRoot artifacts.

## PRoot baseline

Pinned source metadata:

- termux/termux-packages@32f2b3a6c7a1f2a6d068e523d6248e6b4a334d68
- PRoot 5.1.107.92
- libandroid-shmem 0.7
- libtalloc 2.4.3

No third-party PRoot binary is committed or approved.

## Version

- versionCode: 8
- versionName: 0.1.0-alpha8
- compile/target SDK: 37
- min SDK: 26
- NDK: 29.0.14206865
- CMake: 3.22.1

## CI status

GitHub-hosted Android CI and PRoot source-audit jobs still fail before their first declared step. That remains infrastructure evidence only, not a demonstrated source/build failure.

## Next gates

1. runner allocation;
2. independent source archive hash audit;
3. real ARM64 PRoot quarantine build;
4. ELF/DT_NEEDED/SONAME report;
5. license review from pinned sources;
6. produced artifact SHA-256 lock;
7. physical Android substrate/link test;
8. only then consider an explicit artifact approval commit;
9. executor still remains a separate later gate.
