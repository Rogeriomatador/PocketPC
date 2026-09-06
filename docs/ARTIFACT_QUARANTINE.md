# PRoot artifact quarantine — Alpha 8

Status: **IMPLEMENTED policy / REAL ARTIFACT BUILD NOT YET VALIDATED**

## Purpose

A successful build is not sufficient evidence for APK inclusion.

Every produced PRoot component must first enter a quarantine directory outside app/src. Nothing in quarantine is treated as approved.

## Source authority

The build script refuses a termux-packages checkout unless HEAD is exactly:

~~~text
32f2b3a6c7a1f2a6d068e523d6248e6b4a334d68
~~~

The source archive audit runs before the build.

## Quarantine output

The planned ARM64 build extracts raw produced artifacts into:

~~~text
quarantine/arm64-v8a/
  proot
  loader
  libandroid-shmem.so
  libtalloc.so*
~~~

No packaging alias is applied yet.

## ELF contract

ARTIFACT_CONTRACT.json requires:

- ELF64;
- little endian;
- EM_AARCH64 / machine 183;
- ET_EXEC or ET_DYN;
- no RPATH;
- no RUNPATH.

It explicitly rejects common glibc dependencies:

- libc.so.6
- libpthread.so.0
- librt.so.1
- libdl.so.2
- libm.so.6
- ld-linux-aarch64.so.1

## Dynamic dependency discovery

The auditor records all DT_NEEDED entries and SONAME.

Dependencies not in the Android system allowlist are reported as review-required rather than silently accepted.

This is particularly important for talloc because the final Android SONAME/filename must come from the exact produced ELF.

## Reports

audit-proot-artifacts.py emits a JSON report with:

- file name;
- file bytes;
- SHA-256;
- ELF class/endian/type/machine;
- DT_NEEDED;
- SONAME;
- RPATH;
- RUNPATH;
- policy failures;
- unreviewed dependencies.

make-proot-artifact-candidate.py converts a successful structural report into a review-only candidate.

The candidate always contains:

~~~text
promotion.approved = false
~~~

It cannot enable the runtime.

## Workflow

PRoot Quarantine Build is workflow_dispatch only.

It:

1. checks out PocketPC;
2. checks out the exact pinned termux-packages commit;
3. verifies tools;
4. runs source audit;
5. builds aarch64 packages inside Termux's Docker flow;
6. extracts relevant artifacts;
7. runs ELF audit;
8. creates a review-only candidate;
9. uploads JSON reports only;
10. proves no PRoot binary entered the repository tree.

## Approval boundary

ExecutionSubstrateProbe contains a separate fail-closed artifact approval flag.

File presence plus a good ELF report still does not imply approval.

A future approval commit must cite:

- source audit evidence;
- artifact hashes;
- dependency review;
- license review;
- packaging contract;
- physical-device evidence.
