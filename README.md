# PocketPC — 0.1.0-alpha6

PocketPC is an experimental Android desktop/runtime project aimed at turning a phone into a desktop workstation while preserving honest evidence labels.

## Evidence ladder

- **DESIGN**
- **IMPLEMENTED**
- **STATICALLY VALIDATED**
- **CI VALIDATED**
- **DEVICE TESTED**
- **BENCHMARKED**

A higher label is never inferred from a lower one.

## Alpha 6 — IMPLEMENTED source

Alpha 6 includes all Alpha 5 runtime-package and safe-rootfs work, plus:

- structured host/guest bind model;
- host-path allowlist validation;
- reserved guest mount protection;
- normalized guest path validation;
- read-only binds fail closed until real semantics are implemented;
- minimal Linux environment whitelist;
- explicit PROOT_LOADER path for the proposed packaged ARM64 loader alias;
- PRoot argv planner using List<String> instead of shell-string concatenation;
- app-private runtime home and tmp bind planning;
- one-shot process supervisor with timeout and bounded logs;
- process environment clearing before explicit variables;
- output drain designed to avoid pipe deadlock;
- execution remains explicitly disabled by EXECUTOR_NOT_ENABLED;
- unit-test sources for bind policy, environment, argv planning and supervisor behavior.

## Runtime pipeline

~~~text
manifest/archive
   ↓
STAGED_VERIFIED
   ↓
safe TAR/TAR.GZ extraction
   ↓
INSTALLED_DATA
   ↓
bind/env/argv planning
   ↓
[EXECUTOR_NOT_ENABLED]
~~~

Linux is still **not claimed executable**.

## Static validation

The pure Kotlin schema-v2 + safe TAR smoke passed locally:

- valid schema v2 accepted;
- valid TAR extracted;
- output file content matched;
- ../ traversal archive rejected;
- no escaped file was created.

That does not validate Android, Gradle, JNI, PRoot or a device.

## PRoot supply-chain baseline

PocketPC now pins upstream source metadata in third_party/proot/LOCK.json.

Recipe authority:

- termux/termux-packages
- commit 32f2b3a6c7a1f2a6d068e523d6248e6b4a334d68
- commit timestamp 2026-09-06T09:56:57Z

Pinned components:

- PRoot 5.1.107.92 — GPL-2.0
- libandroid-shmem 0.7 — BSD 3-Clause
- libtalloc 2.4.3 — GPL-3.0

No PRoot binary is bundled. The pinned archive hashes currently have SOURCE_METADATA_LOCKED evidence from the upstream recipes; scripts/audit-proot-sources.py is intended to independently download and recalculate them when a runner/network environment is available.

## Proposed ARM64 packaging contract — DESIGN

- proot executable alias: libproot.so
- upstream 64-bit loader alias: libproot_loader.so
- env: PROOT_LOADER=<nativeLibraryDir>/libproot_loader.so
- no loader32 in the first gate

Upstream PRoot explicitly supports PROOT_LOADER for overriding its unbundled loader path.

## Graphics

Native Vulkan capability enumeration exists. No renderer/vGPU performance claim exists yet.

## Version

- app versionCode: 6
- app versionName: 0.1.0-alpha6
- compile/target SDK: 37
- min SDK: 26
- NDK: 29.0.14206865
- CMake: 3.22.1

## CI status

Still **not CI validated**. GitHub-hosted runs have been failing before the first workflow step with no job steps/logs. That remains Issue #3 and is not evidence of source compilation failure.

## Next gate

1. CI runner allocation.
2. independent source archive audit from the supply-chain lock.
3. Android build/lint/tests.
4. device-test native host and Vulkan probe.
5. device-test schema-v2 rootfs install.
6. complete PRoot build/license/artifact audit in Issue #4.
7. implement guest link semantics.
8. enable a tightly gated non-interactive PRoot smoke command.
9. PTY and interactive shell only after the one-shot path is proven.
