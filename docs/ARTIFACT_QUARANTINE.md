# PRoot artifact quarantine — Alpha 9

Status: **IMPLEMENTED POLICY / REAL ARTIFACT BUILD NOT YET VALIDATED**

## Quarantine principle

Produced third-party binaries remain outside app/src and third_party.

The manual build pipeline may produce raw artifacts such as:

- proot
- loader
- libandroid-shmem.so
- libtalloc.so*

Those are audit inputs only.

## ELF audit

The auditor records:

- SHA-256;
- ELF class/endian/type/machine;
- DT_NEEDED;
- SONAME;
- RPATH/RUNPATH.

It rejects:

- wrong architecture;
- invalid ELF type;
- RPATH/RUNPATH;
- glibc-style dependencies.

## Android packaging blockers

A structurally valid ELF may still be blocked for Android packaging.

Alpha 9 explicitly reports androidPackagingBlockers when, for example:

- raw talloc runtime file is libtalloc.so.2;
- talloc SONAME is versioned;
- PRoot or another artifact DT_NEEDED references libtalloc.so.N.

Android packaging expects native libraries under lib/<abi>/lib<name>.so.

Allowed resolution approaches are intentionally narrow:

1. audited rebuild with Android-packagable SONAME and matching DT_NEEDED;
2. audited build eliminating the dynamic talloc dependency, e.g. an acceptable static-link design.

Renaming a versioned library file without resolving DT_NEEDED is not considered a solution.

## Candidate lock

make-proot-artifact-candidate.py emits schema v2 evidence with:

- source lock hash;
- artifact contract hash;
- ELF status;
- unreviewed dependencies;
- Android packaging blockers;
- artifact metadata;
- unresolved license review;
- unresolved device review;
- promotion.approved=false.

Candidate evidence cannot enable runtime execution.

## Final artifact lock

A future reviewed ARTIFACTS.lock.json must be a separate explicit artifact.

Alpha 9 does not generate or approve that final lock automatically.
